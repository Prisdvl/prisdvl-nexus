package com.lky.nexus.ai.model;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 用户自带嵌入模型的白名单与维度约束（BYOK 路线 A）。
 *
 * <p><b>为什么这条规则要单独成类</b>：它必须在<b>两个方向</b>上都成立——
 * <ul>
 *   <li><b>保存时</b>（{@code UserEmbeddingConfigService.upsert}）：让用户立刻知道
 *       "这个模型/维度不允许"，而不是保存成功、等到某次检索才炸（错误码 {@code 1001}）；</li>
 *   <li><b>使用时</b>（{@code KnowledgeEmbeddingResolver}）：库里已经躺着一行不符合当前白名单的配置
 *       （改过配置、直接改库、白名单收紧）时，拒绝执行而不是拿它去写向量（错误码 {@code 4006}）。</li>
 * </ul>
 * 两处各写一遍判断最容易一边改一边忘，所以规则与文案都收敛在这里，调用方只选错误码。
 *
 * <p>维度为什么必须是硬约束：向量列是 {@code vector(1024)}，知识库建库时把
 * {@code provider/model/dim} 冻结进 {@code kb_knowledge_base}（ADR-008）。
 * 允许别的维度等于允许写入宽度错的向量——不会报错，只会让检索结果莫名其妙。
 * 因此**不截断、不补零、不降级**，直接拒绝；多维度分表属路线 B，本轮不实施。
 */
@Component
public class EmbeddingByokPolicy {

    private final AiModelProperties properties;

    public EmbeddingByokPolicy(AiModelProperties properties) {
        this.properties = properties;
    }

    /** 白名单快照（{@code provider:model}），供 API 下发给前端选择器。 */
    public List<String> allowedModels() {
        return List.copyOf(properties.getByok().getEmbedding().getAllowedModels());
    }

    /** 平台允许的唯一嵌入维度（默认 1024）。 */
    public int requiredDimensions() {
        return properties.getByok().getEmbedding().getRequiredDimensions();
    }

    /** {@code provider:model} 是否在白名单内（空白输入一律视为不允许）。 */
    public boolean isAllowed(String provider, String model) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(model)) {
            return false;
        }
        return allowedModels().contains(provider.trim() + ":" + model.trim());
    }

    /** 是否允许使用白名单之外的自定义 OpenAI 兼容端点（保存时以探针校验维度）。 */
    public boolean allowsCustomEndpoint() {
        return properties.getByok().getEmbedding().isAllowCustomEndpoint();
    }

    /** 探针是否允许访问内网 / 本机地址（仅开发与测试 profile 打开）。 */
    public boolean allowsPrivateEndpoints() {
        return properties.getByok().getEmbedding().isAllowPrivateEndpoints();
    }

    /**
     * 违反约束的原因；合规时返回空。
     *
     * @return 可直接展示给用户的中文原因（含当前白名单与允许的维度）
     */
    public Optional<String> violationOf(String provider, String model, Integer dimensions) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(model)) {
            return Optional.of("嵌入模型需要同时给出 provider 与模型名");
        }
        if (!isAllowed(provider, model)) {
            return Optional.of("嵌入模型 %s:%s 不在平台允许的白名单内。当前白名单：%s"
                    .formatted(provider.trim(), model.trim(), allowedModels()));
        }
        return dimensionViolation(dimensions);
    }

    private Optional<String> dimensionViolation(Integer dimensions) {
        int required = requiredDimensions();
        if (dimensions == null || dimensions != required) {
            return Optional.of(("嵌入维度必须是 %d 维（当前为 %s）。向量列与知识库冻结维度都按 %d 维建立，"
                    + "平台不会静默截断、补零或改用其他维度。")
                    .formatted(required, dimensions == null ? "未填" : dimensions.toString(), required));
        }
        return Optional.empty();
    }

    /**
     * 这一点能不能真的拿来跑嵌入：白名单内的 {@code provider:model}，或者开启了自定义端点且带了 Base URL。
     * 保存路径与使用路径共用这一份判定，避免「存得进去、跑起来报白名单」的两套规则。
     *
     * @param baseUrl 自定义端点必填；白名单路径可空
     */
    public void requireUsable(String provider, String model, Integer dimensions, String baseUrl,
                              ErrorCode code) {
        boolean whitelisted = isAllowed(provider, model);
        if (!whitelisted && !(allowsCustomEndpoint() && StringUtils.hasText(baseUrl))) {
            throw new BusinessException(code, violationOf(provider, model, dimensions)
                    .orElse("嵌入模型 %s:%s 不可用".formatted(provider, model)));
        }
        dimensionViolation(dimensions).ifPresent(message -> {
            throw new BusinessException(code, message);
        });
    }

    /**
     * 不合规即抛异常。
     *
     * @param code 由调用方决定语义：保存路径用 {@link ErrorCode#PARAM_INVALID}（用户输入问题），
     *             使用路径用 {@link ErrorCode#MODEL_ROLE_NOT_CONFIGURED}（平台模型配置问题）
     */
    public void requireAllowed(String provider, String model, Integer dimensions, ErrorCode code) {
        violationOf(provider, model, dimensions).ifPresent(message -> {
            throw new BusinessException(code, message);
        });
    }
}
