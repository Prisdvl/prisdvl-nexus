package com.lky.nexus.ai.model;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
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

    /** 工具循环上限的唯一载体：所有手工构建的模型共用同一份，避免各 provider 各配一套。 */
    private final ToolCallingManager toolCallingManager;

    public AiModelRegistry(AiModelProperties properties) {
        this.toolCallingManager = toolCallingManager(properties.getTools());
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

    /**
     * 本次构建实际交给各模型的工具调用管理器。
     *
     * <p>包级可见是刻意的：{@code ToolCallLimitTest} 用它<b>真的撞一次上限</b>，
     * 证明 {@code ai.tools.*} 是生效的配置，而不是"写了但没人读"（这个坑刚踩过，
     * 见 {@link AiModelProperties.ToolLimits} 的注释）。限定可见性是为了不让它变成对外 API——
     * 上限的读取方应该是模型，不是业务代码。
     */
    ToolCallingManager toolCallingManager() {
        return toolCallingManager;
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
    @SuppressWarnings({"deprecation", "removal"})
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

        return OpenAiChatModel.builder()
                .options(optionsBuilder.build())
                .toolCallingManager(toolCallingManager)
                .build();
    }

    /** Ollama 原生协议：模型名可带冒号（如 {@code qwen3:7b}），base-url 由 {@link OllamaApi} 承载。 */
    @SuppressWarnings({"deprecation", "removal"})
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
                .toolCallingManager(toolCallingManager)
                .build();
    }

    /**
     * 按配置构建工具调用管理器——<b>必须显式传</b>给每个模型，否则框架会自己造一个默认的。
     *
     * <p>为什么这件事必须在这里做：Agent 的工具循环是"模型与工具来回对话若干轮"，
     * 没有上限就是一个可能烧光额度的死循环。上限<b>确实存在</b>（框架
     * {@code DefaultToolCallingManager} 给 40 / 150），但"存在"与"看得见、调得动、有测试"
     * 是三件事。这里把它从"框架内部默认值"变成 {@code ai.tools} 里的显式配置，
     * 并在启动日志里打出来——排查"为什么这次只检索了 3 次就不动了"时，
     * 不该需要读框架源码。
     *
     * <p>{@code builder.toolCallingManager(...)} 在 2.0.1 里标了
     * {@code @Deprecated(forRemoval=true)}，但框架<b>自己的</b>
     * {@code OpenAiChatAutoConfiguration} 也在调它（javap 核对过）。
     * 不走它就只能接受框架内置的默认值（那份默认值读不到本项目的配置），
     * 所以这里沿用到正式替代 API 出现为止——将来它真被删掉时，替代方案是
     * "把 ToolCallingManager 做成 bean 再注入"，而本项目手工建模型，那条路要另想办法。
     *
     * <p>超限行为固定为 {@code THROW}，不做成开关：另一个选项
     * （{@code RETURN_ERROR_RESPONSE}）会让模型"带着没有结果的工具调用继续作答"，
     * 也就是一个看起来正常、其实没检索到东西的回答——这正是本项目一路在防的静默降级。
     * 抛出来才能变成一条如实的 5002 错误（见 {@code FailureClassifier}）。
     */
    private static ToolCallingManager toolCallingManager(AiModelProperties.ToolLimits limits) {
        ToolCallingManager manager = ToolCallingManager.builder()
                .maxCallsPerTool(limits.getMaxCallsPerToolDefault())
                .maxTotalToolCalls(limits.getMaxTotalToolCalls())
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                .build();
        log.info("工具循环上限：单工具 {} 次 / 一次生成合计 {} 次，超限抛出（配置在 ai.tools，"
                        + "注意 spring.ai.tools.limits.* 在本项目不起作用，原因见 AiModelProperties.ToolLimits）",
                limits.getMaxCallsPerToolDefault(), limits.getMaxTotalToolCalls());
        return manager;
    }
}
