package com.lky.nexus.ai.model;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 嵌入客户端工厂：业务代码通过<b>逻辑角色</b>获取 {@link EmbeddingClient}（ADR-003）。
 *
 * <pre>
 * EmbeddingClient client = embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT);
 * </pre>
 *
 * <p>与 {@link ChatClientFactory} 同样的约束：业务代码里不允许出现具体模型类名，
 * 换供应商只改配置。
 *
 * <p><b>当前支持范围</b>：Ollama 原生协议（本项目的嵌入角色就是本地 {@code bge-m3}）。
 * OpenAI 兼容协议暂不支持——Spring AI 2.0 的 {@code OpenAiEmbeddingModel} 需要自行构建
 * 官方 SDK 客户端（baseUrl + 凭证 + 传输层），这是一块独立工作；
 * 与其写一段没验证过的代码，不如在这里明确报错，并把扩展点留清楚。
 */
@Component
public class EmbeddingClientFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClientFactory.class);

    private final AiModelProperties properties;
    private final Map<String, EmbeddingClient> clientsByRole = new ConcurrentHashMap<>();

    public EmbeddingClientFactory(AiModelProperties properties) {
        this.properties = properties;
    }

    /**
     * 按逻辑角色取嵌入客户端（线程安全，按角色缓存）。
     *
     * @throws BusinessException 4006：角色未配置 / 模型不存在 / 未声明 EMBEDDING 能力 / 协议暂不支持
     */
    public EmbeddingClient get(String role) {
        return clientsByRole.computeIfAbsent(role, this::create);
    }

    private EmbeddingClient create(String role) {
        String reference = properties.getRoles().get(role);
        if (!StringUtils.hasText(reference)) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "未配置逻辑角色 %s".formatted(role));
        }
        ModelRef ref = ModelRef.parse(reference);
        AiModelProperties.Provider provider = properties.findProvider(ref.provider())
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                        "找不到供应商 %s（角色 %s）".formatted(ref.provider(), role)));

        AiModelProperties.ModelEntry entry = provider.getModels().stream()
                .filter(model -> ref.model().equals(model.getName()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                        "供应商 %s 下没有模型 %s（角色 %s）".formatted(ref.provider(), ref.model(), role)));

        if (!entry.supports(AiModelProperties.ModelCapability.EMBEDDING)) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "模型 %s 未声明 EMBEDDING 能力，不能用于嵌入角色 %s".formatted(ref.value(), role));
        }

        EmbeddingModel model = switch (provider.getType()) {
            case OLLAMA -> buildOllamaEmbeddingModel(provider, entry);
            case OPENAI_COMPATIBLE -> throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    ("嵌入角色 %s 指向了 OpenAI 兼容协议的模型 %s，这是当前尚未支持的路径"
                            + "（Spring AI 2.0 需要自行构建官方 SDK 客户端）。"
                            + "请把 embedding.default 指向 Ollama 模型，例如 ollama:bge-m3。")
                            .formatted(role, ref.value()));
        };

        log.info("为角色 {} 构建嵌入客户端：{}（声明维度 {}）", role, ref.value(), entry.getDimensions());
        return new EmbeddingClient(ref.value(), model, entry.getDimensions());
    }

    /** Ollama 原生协议：模型名可带冒号（如 {@code bge-m3}），base-url 由 {@link OllamaApi} 承载。 */
    private EmbeddingModel buildOllamaEmbeddingModel(AiModelProperties.Provider provider,
                                                     AiModelProperties.ModelEntry entry) {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(provider.getBaseUrl())
                .build();
        OllamaEmbeddingOptions options = OllamaEmbeddingOptions.builder()
                .model(entry.getName())
                .build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .build();
    }
}
