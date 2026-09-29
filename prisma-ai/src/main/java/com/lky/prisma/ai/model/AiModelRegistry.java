package com.lky.prisma.ai.model;

import com.lky.prisma.ai.config.AiModelProperties;
import com.lky.prisma.common.api.ErrorCode;
import com.lky.prisma.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 模型注册表：按 {@code ai.providers} 在启动时构建并缓存多个 {@link ChatModel}（ADR-003）。
 *
 * <p>为什么不用 Spring AI starter 的自动配置：
 * starter 只提供一个 {@code ChatModel} bean，而本项目需要同时存在对话模型、Agent 模型、
 * 评测裁判模型（甚至分属不同供应商）。官方为此提供了 {@code spring.ai.model.chat=none}
 * 来关闭自动配置（见 application.yml 注释），由本类接管构建。
 *
 * <p><b>构建不等于可用</b>：构建 {@code ChatModel} 只是准备好客户端，不会发起网络请求。
 * 因此即使没有 API Key，应用也能正常启动——这时对应的 provider 会被<b>跳过并告警</b>，
 * 真正使用到该角色时才抛出明确的 4006 错误。这样避免了两种糟糕体验：
 * ① 没有 Key 就完全起不来（本地开发被卡住）；
 * ② 静默用一个空 Key 去请求，最后收到供应商晦涩的 401。
 */
public class AiModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiModelRegistry.class);

    private final Map<String, ChatModel> chatModels;

    public AiModelRegistry(AiModelProperties properties) {
        Map<String, ChatModel> built = new LinkedHashMap<>();
        for (AiModelProperties.Provider provider : properties.getProviders()) {
            if (provider.getType() == null) {
                log.warn("provider [{}] 未指定 type，已跳过", provider.getName());
                continue;
            }
            if (requiresApiKey(provider) && !StringUtils.hasText(provider.getApiKey())) {
                log.warn("provider [{}] 未配置 api-key，已跳过其全部模型；"
                        + "使用到相关角色时将报 4006。请在 .env / application-local.yml 中配置。", provider.getName());
                continue;
            }
            for (AiModelProperties.ModelEntry entry : provider.getModels()) {
                if (!entry.supports(AiModelProperties.ModelCapability.CHAT)) {
                    // 嵌入模型在 Phase 3 单独注册，这里只处理对话模型
                    continue;
                }
                ModelRef ref = new ModelRef(provider.getName(), entry.getName());
                built.put(ref.value(), buildChatModel(properties, provider, entry));
                log.info("已注册对话模型 {} (type={}, contextWindow={})",
                        ref.value(), provider.getType(), entry.getContextWindow());
            }
        }
        this.chatModels = Map.copyOf(built);

        if (chatModels.isEmpty()) {
            log.warn("没有任何可用的对话模型：请检查 ai.providers 配置与 API Key");
        }
    }

    /** 按模型引用取 {@link ChatModel}；不存在时抛 4006 并列出已注册的 key，便于排查拼写错误。 */
    public ChatModel requireChatModel(String rawReference) {
        ModelRef ref = ModelRef.parse(rawReference);
        ChatModel model = chatModels.get(ref.value());
        if (model == null) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "模型 %s 未注册。已注册：%s".formatted(ref.value(), chatModels.keySet()));
        }
        return model;
    }

    /** 已注册的模型引用集合，供健康检查与观测页面展示。 */
    public Set<String> registeredChatModels() {
        return chatModels.keySet();
    }

    private boolean requiresApiKey(AiModelProperties.Provider provider) {
        return provider.getType() == AiModelProperties.ProviderType.OPENAI_COMPATIBLE;
    }

    private ChatModel buildChatModel(AiModelProperties properties,
                                     AiModelProperties.Provider provider,
                                     AiModelProperties.ModelEntry entry) {
        return switch (provider.getType()) {
            case OPENAI_COMPATIBLE -> buildOpenAiCompatibleChatModel(properties, provider, entry);
            case OLLAMA -> buildOllamaChatModel(properties, provider, entry);
        };
    }

    /**

     * OpenAI 兼容协议：{@code base-url} 指向供应商端点即可切换 DeepSeek / 百炼 / 智谱 / SiliconFlow / vLLM。
     *
     * <p>构建方式经 v2.0.1 源码核对：{@code OpenAiChatModel.builder().options(OpenAiChatOptions)}，
     * 其中 {@code OpenAiChatOptions.Builder} 提供 {@code baseUrl(String)} 与 {@code apiKey(String)}。
     */
    private ChatModel buildOpenAiCompatibleChatModel(AiModelProperties properties,
                                                     AiModelProperties.Provider provider,
                                                     AiModelProperties.ModelEntry entry) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .model(entry.getName())
                .temperature(properties.getDefaults().getTemperature());

        if (properties.getDefaults().getMaxTokens() != null) {
            optionsBuilder.maxTokens(properties.getDefaults().getMaxTokens());
        }
        // 网关要求的附加请求头（例如 OpenCode Zen 的 x-opencode-session 与自定义 User-Agent）
        if (provider.getHeaders() != null && !provider.getHeaders().isEmpty()) {
            optionsBuilder.customHeaders(provider.getHeaders());
        }

        return OpenAiChatModel.builder().options(optionsBuilder.build()).build();
    }

    /** Ollama 原生协议：模型名可带冒号（如 {@code qwen3:7b}），base-url 由 {@link OllamaApi} 承载。 */
    private ChatModel buildOllamaChatModel(AiModelProperties properties,
                                          AiModelProperties.Provider provider,
                                          AiModelProperties.ModelEntry entry) {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(provider.getBaseUrl())
                .build();
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(entry.getName())
                .temperature(properties.getDefaults().getTemperature())
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .build();
    }
}
