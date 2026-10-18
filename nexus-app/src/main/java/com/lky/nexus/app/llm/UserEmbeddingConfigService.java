package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lky.nexus.ai.model.EmbeddingByokPolicy;
import com.lky.nexus.app.security.CredentialCipher;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户自定义<b>嵌入</b>模型凭据服务（BYOK 路线 A）。
 *
 * <p>负责读 / 写当前用户那一行（{@code uk_user_embedding_user} 1:1）。
 * 真正消费这份凭据的是 {@link DbUserEmbeddingCredentialsProvider}，它实现 nexus-ai 的
 * {@code UserEmbeddingCredentialsProvider} 端口，供 nexus-knowledge 的
 * {@code KnowledgeEmbeddingResolver} 使用（建库冻结 / 写入向量化 / 查询向量化三条路径）。
 *
 * <p>三件必须在保存时就把住的事：
 * <ol>
 *   <li><b>白名单与维度</b>（{@link EmbeddingByokPolicy}）：嵌入模型决定向量空间，
 *       不在白名单里的 {@code provider:model} 或非平台维度直接 1001，
 *       而不是"存下来，等检索时才炸"。</li>
 *   <li><b>字段级加密</b>（{@link CredentialCipher}）：api_key 落库即密文，
 *       与 {@code user_llm_config} 同一口径；回显掩码打在明文上。</li>
 *   <li><b>启用时四项齐全</b>：provider / 模型名 / Base URL / Key，
 *       否则存了一个"一启用就 4006"的坏配置。</li>
 * </ol>
 *
 * <p><b>改配置不会自动迁移旧知识库</b>：这里只改"以后用什么"，已建知识库的
 * {@code embedding_provider / model / dim} 是冻结值（ADR-008）。换了模型之后旧库检索会
 * 明确 4006，必须显式重建向量或新建知识库——这是刻意的契约，不是遗漏。
 */
@Service
public class UserEmbeddingConfigService {

    private static final Logger log = LoggerFactory.getLogger(UserEmbeddingConfigService.class);

    private final UserEmbeddingConfigMapper userEmbeddingConfigMapper;
    private final CredentialCipher credentialCipher;
    private final EmbeddingByokPolicy byokPolicy;

    public UserEmbeddingConfigService(UserEmbeddingConfigMapper userEmbeddingConfigMapper,
                                      CredentialCipher credentialCipher,
                                      EmbeddingByokPolicy byokPolicy) {
        this.userEmbeddingConfigMapper = userEmbeddingConfigMapper;
        this.credentialCipher = credentialCipher;
        this.byokPolicy = byokPolicy;
    }

    /** 读当前用户的配置（apiKey 掩码回显；未配置时也返回白名单，便于前端直接渲染表单）。 */
    public UserEmbeddingConfigResponse get(Long userId) {
        int required = byokPolicy.requiredDimensions();
        UserEmbeddingConfigPO po = findByUser(userId);
        if (po == null) {
            return new UserEmbeddingConfigResponse(null, null, null, required, false, false, null,
                    byokPolicy.allowedModels(), required);
        }
        // 掩码必须打在**明文**上（与对话侧同一处理）：打在密文上会把密文片段当成 Key 回显出去
        String plainKey = credentialCipher.decrypt(po.getApiKey());
        upgradeLegacyRow(po, plainKey);
        return new UserEmbeddingConfigResponse(
                po.getProvider(), po.getModelName(), po.getBaseUrl(),
                po.getDimensions() == null ? required : po.getDimensions(),
                Integer.valueOf(1).equals(po.getEnabled()),
                StringUtils.hasText(plainKey),
                UserLlmConfigService.mask(plainKey),
                byokPolicy.allowedModels(), required);
    }

    /**
     * 保存（upsert）当前用户配置。
     *
     * <p>apiKey 留空 = 保留旧值；provider / modelName / baseUrl 传空串即清空。
     */
    @Transactional
    public UserEmbeddingConfigResponse upsert(Long userId, UserEmbeddingConfigRequest request) {
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String provider = trimmedToNull(request.provider());
        String modelName = trimmedToNull(request.modelName());
        String baseUrl = trimmedToNull(request.baseUrl());
        int required = byokPolicy.requiredDimensions();
        int dimensions = request.dimensions() == null ? required : request.dimensions();

        // 白名单 + 维度：用户只要碰了 provider / 模型名 / 维度里的任意一项就校验。
        // 三项全空表示"清空配置"，这时候不该拿白名单去拦一个空值。
        if (provider != null || modelName != null || request.dimensions() != null) {
            byokPolicy.requireAllowed(provider, modelName, dimensions, ErrorCode.PARAM_INVALID);
        }

        UserEmbeddingConfigPO po = findByUser(userId);
        boolean isNew = po == null;
        if (isNew) {
            po = new UserEmbeddingConfigPO();
            po.setUserId(userId);
            po.setEnabled(0);
        }

        po.setProvider(provider);
        po.setModelName(modelName);
        po.setBaseUrl(baseUrl);
        po.setDimensions(dimensions);

        // apiKey 留空 = 保留已有（历史明文行顺手升级为密文）；填了才覆盖。
        // 刻意不无脑重加密：GCM 用随机 IV，无脑重加密会让"只改了模型名"也换掉密文。
        String plainKey = credentialCipher.decrypt(po.getApiKey());
        if (StringUtils.hasText(request.apiKey())) {
            plainKey = request.apiKey().trim();
            po.setApiKey(credentialCipher.encrypt(plainKey));
        } else if (StringUtils.hasText(plainKey) && !credentialCipher.isEncrypted(po.getApiKey())) {
            po.setApiKey(credentialCipher.encrypt(plainKey));
        }

        if (enabled) {
            validateReady(provider, modelName, baseUrl, plainKey);
        }
        po.setEnabled(enabled ? 1 : 0);

        if (isNew) {
            userEmbeddingConfigMapper.insert(po);
        } else {
            userEmbeddingConfigMapper.updateById(po);
        }
        log.info("用户 {} 的自定义嵌入模型配置已保存：enabled={}, provider={}, model={}, dimensions={}",
                userId, enabled, po.getProvider(), po.getModelName(), po.getDimensions());
        return get(userId);
    }

    private void validateReady(String provider, String modelName, String baseUrl, String apiKey) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(modelName)
                || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "启用自定义嵌入模型需要填完整：provider、模型名、Base URL 与 API Key"
                            + "（若 Key 输入框为空，请重新填写）");
        }
    }

    /**
     * 把「加密之前写下」的历史明文行就地升级为密文（幂等；与对话侧同一策略）。
     *
     * <p>只在这一行真的被读出来时做，<b>不</b>放在模型/检索热路径上——
     * 那条路径只读不写。KEK 未配置时这里会抛 9999（拒绝以明文保存），
     * 这正是"未配置"应有的表现：在用户操作的位置报错，而不是把明文继续留在库里。
     */
    private void upgradeLegacyRow(UserEmbeddingConfigPO po, String plainKey) {
        if (!StringUtils.hasText(plainKey) || credentialCipher.isEncrypted(po.getApiKey())) {
            return;
        }
        UserEmbeddingConfigPO patch = new UserEmbeddingConfigPO();
        patch.setId(po.getId());
        patch.setApiKey(credentialCipher.encrypt(plainKey));
        userEmbeddingConfigMapper.updateById(patch);
        log.info("用户 {} 的历史明文嵌入凭据已升级为密文", po.getUserId());
    }

    private UserEmbeddingConfigPO findByUser(Long userId) {
        return userEmbeddingConfigMapper.selectOne(new LambdaQueryWrapper<UserEmbeddingConfigPO>()
                .eq(UserEmbeddingConfigPO::getUserId, userId));
    }

    private static String trimmedToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
