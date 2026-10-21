package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingByokPolicy;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import com.lky.nexus.ai.model.ModelRef;
import com.lky.nexus.ai.model.UserEmbeddingCredentials;
import com.lky.nexus.ai.model.UserEmbeddingCredentialsProvider;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 解析"该知识库应该用哪个嵌入客户端 / 该冻结哪个嵌入模型"，并校验模型一致性。
 *
 * <p>抽成独立组件是因为它被三条路径共用：<b>建库冻结</b>、<b>写入侧</b>（向量化切片）与
 * <b>读取侧</b>（把查询语句转成向量）。它们必须用同一个模型——
 * 查询用模型 A、索引用模型 B，向量空间不一致，检索结果会离谱且不报错。
 * 这种"几条路径各写一遍校验"的代码最容易一边改一边忘，所以收敛到一处。
 *
 * <h2>生效配置（BYOK 路线 A）</h2>
 * 按<b>知识库所有者</b>解析，优先级如下：
 * <ol>
 *   <li>该用户有一行启用的 {@code user_embedding_config} → 用它（他自己的端点与 Key），
 *       但必须过白名单与固定维度，并与其知识库冻结的 {@code provider:model:dim} 一致；</li>
 *   <li>否则回落服务端全局角色 {@code embedding.default}（与 BYOK 上线前完全一致）。</li>
 * </ol>
 * 用"所有者"而不是"当前请求者"作为查询键：知识库归属唯一用户（跨用户访问一律 404），
 * 冻结发生在所有者建库时、写入发生在所有者导入文档时、查询发生在所有者检索时——
 * 三处都该用他自己的凭据，这样"用户 A 的 Key 只会用于 A 的库"是结构性成立，
 * 不靠调用方记得传对 userId。
 *
 * <h2>不变式（保持不变）</h2>
 * <b>查询模型必须等于知识库冻结模型</b>。用户换了自己的嵌入模型之后，旧知识库不会
 * "自动切过去"，而是直接 4009 拒绝（409，可修复的资源冲突）——必须显式重建索引
 * （{@code POST /api/knowledge-bases/{id}/index/recreate}）或新建知识库。
 * "用自己的 Key"不是绕过冻结的理由：混用两种模型产生的向量会让检索静默错乱。
 *
 * <p><b>多库扇出时的跨库可比性</b>：一次检索里的每个库都必须等于"同一个生效配置"，
 * 通过校验的库之间才必然彼此一致，向量分数才可比（见
 * {@code RecallSupport#merge} 与 {@code ChunkSearchServiceImpl#precheckEmbeddingModels}）。
 * 同一个人先后用两个不同模型建过库（中途改过 BYOK 配置）时，旧库会被明确拒绝，
 * 而不是被悄悄混进结果。
 */
@Component
public class KnowledgeEmbeddingResolver {

    private static final String GLOBAL_ROLE = AiModelProperties.Role.EMBEDDING_DEFAULT;

    private final AiModelProperties aiModelProperties;
    private final EmbeddingClientFactory embeddingClientFactory;
    private final EmbeddingByokPolicy byokPolicy;
    /** 可为 null：nexus-knowledge / nexus-ai 被单独引用时没有这个 bean，此时只用全局配置。 */
    private final ObjectProvider<UserEmbeddingCredentialsProvider> userCredentialsProvider;

    /**
     * 唯一的构造器（构造器注入由 Spring 完成）。
     *
     * <p>刻意<b>不</b>提供"省略用户凭据端口"的便捷重载：本类是 {@code @Component}，
     * 多个未被 {@code @Autowired} 标注的构造器且没有无参构造器时，Spring 无法决定用哪个，
     * 会直接 {@code BeanInstantiationException: No default constructor found} 启动失败
     * （实测于 2026-10-18）。纯单测要构造它时显式传 {@code null} 作为端口即可。
     */
    public KnowledgeEmbeddingResolver(AiModelProperties aiModelProperties,
                                     EmbeddingClientFactory embeddingClientFactory,
                                     EmbeddingByokPolicy byokPolicy,
                                     ObjectProvider<UserEmbeddingCredentialsProvider> userCredentialsProvider) {
        this.aiModelProperties = aiModelProperties;
        this.embeddingClientFactory = embeddingClientFactory;
        this.byokPolicy = byokPolicy;
        this.userCredentialsProvider = userCredentialsProvider;
    }

    /**
     * 解析某知识库该用的嵌入客户端。
     *
     * @throws BusinessException 4009：生效配置与知识库冻结的嵌入模型/维度不一致（409：可修复的资源冲突）；
     *                           4006：用户自带的模型不在白名单内 / 维度不是平台允许的维度（服务端配置问题）
     */
    public EmbeddingClient resolve(KnowledgeBasePO knowledgeBase) {
        Optional<UserEmbeddingCredentials> userCredentials = findUserCredentials(knowledgeBase.getUserId());
        if (userCredentials.isPresent()) {
            return resolveWithUserCredentials(knowledgeBase, userCredentials.get());
        }
        return resolveWithGlobalRole(knowledgeBase);
    }

    /**
     * 解析某用户<b>当前生效</b>的嵌入模型（建库冻结用）。
     *
     * <p>与 {@link #resolve(KnowledgeBasePO)} 共用同一段解析，所以"建库时冻结什么"与
     * "检索时要什么"不可能对不上——这是建库后立刻能用自己模型的前提。
     *
     * @throws BusinessException 4006：角色未配置 / 模型不存在 / 未声明 dimensions /
     *                           用户自带配置不在白名单内
     */
    public EffectiveEmbedding effectiveFor(Long userId) {
        return findUserCredentials(userId)
                .map(this::effectiveFromUser)
                .orElseGet(this::effectiveFromGlobalRole);
    }

    /** 用户自带凭据：先过白名单与维度，再与冻结值比对。 */
    private EmbeddingClient resolveWithUserCredentials(KnowledgeBasePO knowledgeBase,
                                                       UserEmbeddingCredentials credentials) {
        EffectiveEmbedding effective = effectiveFromUser(credentials);
        // 传的是"完整从句"而不是名词短语：requireFrozen 的模板是「……，而%s。」，
        // 只给主语会拼出「而用户自带的嵌入配置。」这种缺谓语的句子（2026-10-18 Docker 验收时发现）。
        requireFrozen(knowledgeBase, effective.provider(), effective.model(),
                "用户自带的嵌入配置使用的是 " + effective.provider() + ":" + effective.model());
        // 白名单已保证 dimensions == 平台允许维度；这里再与"这一行的冻结维度"对齐。
        // 例如全局曾经是 768 维、后来用户启用 1024 维的 BYOK：旧库不能拿新维度去查。
        if (knowledgeBase.getEmbeddingDim() != null
                && effective.dimensions() != knowledgeBase.getEmbeddingDim()) {
            // 4009（不是 4006）：库的冻结维度与当前生效配置打架 → 409，可修复的冲突
            throw new BusinessException(ErrorCode.EMBEDDING_FROZEN_MISMATCH,
                    ("用户自带的嵌入配置是 %d 维，而知识库冻结的维度是 %d。"
                            + "向量列与索引都按冻结维度建立，继续检索会得到错乱结果，因此拒绝执行。")
                            .formatted(effective.dimensions(), knowledgeBase.getEmbeddingDim()));
        }
        return embeddingClientFactory.getForUser(credentials);
    }

    /** 服务端全局角色（BYOK 上线前的唯一路径，也是"没配 BYOK"时的回落路径）。 */
    private EmbeddingClient resolveWithGlobalRole(KnowledgeBasePO knowledgeBase) {
        // 与 BYOK 上线前完全一致：只比对 provider:model 字符串，模型是否存在于 ai.providers
        // 交给 EmbeddingClientFactory 判定（那里本来就要按 provider/能力/api-key 校验一遍）。
        // 刻意**不**在这里多查一次 ModelEntry：那是"冻结"才需要的信息（见 effectiveFor）。
        ModelRef ref = requireGlobalReference("");
        requireFrozen(knowledgeBase, ref.provider(), ref.model(), "当前配置的角色 " + GLOBAL_ROLE
                + " 指向 " + ref.value());
        return embeddingClientFactory.get(GLOBAL_ROLE);
    }

    private EffectiveEmbedding effectiveFromUser(UserEmbeddingCredentials credentials) {
        // 与保存侧、客户端工厂共用同一份判定：白名单内，或开启了自定义端点且带 Base URL
        byokPolicy.requireUsable(credentials.provider(), credentials.model(),
                credentials.dimensions(), credentials.baseUrl(), ErrorCode.MODEL_ROLE_NOT_CONFIGURED);
        return new EffectiveEmbedding(credentials.provider(), credentials.model(), credentials.dimensions());
    }

    private EffectiveEmbedding effectiveFromGlobalRole() {
        ModelRef ref = requireGlobalReference("，无法确定嵌入模型与维度");
        AiModelProperties.ModelEntry entry = aiModelProperties.findModel(ref)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                        "找不到嵌入模型 %s，请检查 ai.providers 配置".formatted(ref.value())));
        if (entry.getDimensions() == null || entry.getDimensions() <= 0) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "嵌入模型 %s 未声明 dimensions，无法确定向量维度（向量维度必须建库时冻结）"
                            .formatted(ref.value()));
        }
        return new EffectiveEmbedding(ref.provider(), ref.model(), entry.getDimensions());
    }

    /**
     * 解析全局角色 {@code embedding.default}。
     *
     * @param purposeSuffix 附在错误信息后的用途说明（不同调用点的下一步动作不同）
     */
    private ModelRef requireGlobalReference(String purposeSuffix) {
        String reference = aiModelProperties.getRoles().get(GLOBAL_ROLE);
        if (!StringUtils.hasText(reference)) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "未配置逻辑角色 %s%s".formatted(GLOBAL_ROLE, purposeSuffix));
        }
        return ModelRef.parse(reference);
    }

    /**
     * 冻结一致性检查：知识库建库时冻结的 {@code provider:model} 必须等于生效配置。
     *
     * <p>错误信息刻意保留"冻结的嵌入模型"这个说法：它是用户能搜到的关键词，
     * 也是文档与测试里的措辞。
     */
    private void requireFrozen(KnowledgeBasePO knowledgeBase, String provider, String model, String source) {
        if (!provider.equals(knowledgeBase.getEmbeddingProvider())
                || !model.equals(knowledgeBase.getEmbeddingModel())) {
            // 4009（不是 4006）：请求格式没问题、上游也没坏，是这个库按另一个模型建的 → 409。
            // 用 4006/502 会让"用户可修复的冲突"在监控上被计成上游故障。
            throw new BusinessException(ErrorCode.EMBEDDING_FROZEN_MISMATCH,
                    ("知识库冻结的嵌入模型是 %s:%s，而%s。"
                            + "同一个库里混用两种嵌入模型的向量会让检索结果错乱（且不会报错），"
                            + "因此拒绝执行。请恢复配置，或新建知识库。")
                            .formatted(knowledgeBase.getEmbeddingProvider(), knowledgeBase.getEmbeddingModel(), source));
        }
    }

    private Optional<UserEmbeddingCredentials> findUserCredentials(Long userId) {
        if (userCredentialsProvider == null || userId == null) {
            return Optional.empty();
        }
        UserEmbeddingCredentialsProvider provider = userCredentialsProvider.getIfAvailable();
        if (provider == null) {
            return Optional.empty();
        }
        return provider.find(userId);
    }

    /**
     * 生效的嵌入模型三元组（{@code provider:model} + 维度）。
     *
     * <p>建库冻结与检索校验都用它，所以两处不可能解析出不同的模型。
     */
    public record EffectiveEmbedding(String provider, String model, int dimensions) {
    }
}
