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
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
 * <p><b>支持协议</b>：
 * <ul>
 *   <li>Ollama 原生协议（{@code /api/embed}）：本地模型，如 {@code bge-m3}；</li>
 *   <li>OpenAI 兼容协议（{@code /v1/embeddings}）：阿里云百炼 compatible-mode、
 *       DeepSeek、SiliconFlow、vLLM 等。由 {@code OpenAiEmbeddingModel} 直接构建，
 *       base-url / api-key / dimensions / customHeaders 全部走配置。</li>
 * </ul>
 * 嵌入维度通过 {@code OpenAiEmbeddingOptions.dimensions(...)} 显式下发，确保远端
 * 实际返回维度与 {@code ModelEntry.dimensions} 一致（百炼 text-embedding-v4 默认
 * 1024，也可指定 2048/1536/768/512/256/128/64）。
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

        if (provider.getType() == AiModelProperties.ProviderType.OPENAI_COMPATIBLE
                && !StringUtils.hasText(provider.getApiKey())) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "嵌入角色 %s 指向了 OpenAI 兼容供应商 %s，但它没有配置 api-key。"
                            + "请在 .env 里填入该供应商对应的 API Key。"
                            .formatted(role, ref.provider()));
        }

        EmbeddingModel model = buildWithApplicationClassLoader(() -> switch (provider.getType()) {
            case OLLAMA -> buildOllamaEmbeddingModel(provider, entry);
            case OPENAI_COMPATIBLE -> buildOpenAiCompatibleEmbeddingModel(provider, entry);
        });

        log.info("为角色 {} 构建嵌入客户端：{}（声明维度 {}）", role, ref.value(), entry.getDimensions());
        return new EmbeddingClient(ref.value(), model, entry.getDimensions());
    }

    /**
     * 在**应用类加载器**下执行模型构建（这里只有构建，没有网络调用）。
     *
     * <p><b>为什么需要这段"换类加载器"的代码</b>——`local` profile 实测踩到，2026-09-30：
     * Spring AI 的 {@code AbstractEmbeddingModel} 在<b>静态初始化</b>里要读一张"已知模型维度表"：
     * <pre>
     * new ClassPathResource("/embedding/embedding-model-dimensions.properties")
     * </pre>
     * 而 {@code ClassPathResource} 内部走 {@code ClassUtils.getDefaultClassLoader()}，**优先取线程上下文类加载器（TCCL）**。
     * 在 Spring Boot 可执行 jar 里，这个资源在 {@code BOOT-INF/lib/spring-ai-model-*.jar} <b>内部</b>，
     * 只有 Boot 的 {@code LaunchedClassLoader} 看得见；而工具调用跑在 Reactor {@code boundedElastic} 的
     * **虚拟线程**上，实测那里的 TCCL 是 JDK 的 {@code AppClassLoader}（只看得见 fat jar 根目录）：
     * <pre>
     * PROBE thread=VirtualThread[#152,loomBoundedElastic-14]/runnable@ForkJoinPool-1-worker-2
     *       ambientTCCL=jdk.internal.loader.ClassLoaders$AppClassLoader@6bc7c054 ambientResolves=false
     *       appLoader  =org.springframework.boot.loader.launch.LaunchedClassLoader@1a6c5a9e appResolves=true
     * </pre>
     * 于是静态初始化直接抛
     * {@code IllegalStateException: the embedding dimensions properties file must exist}；
     * 更麻烦的是这个失败**一次性且全局**：类初始化失败后整个 JVM 里该类永久不可用，
     * 之后连 Tomcat 线程上的"向量化"接口也一起变成
     * {@code NoClassDefFoundError: Could not initialize class org.springframework.ai.ollama.OllamaEmbeddingModel}。
     *
     * <p>也就是说：谁先第一次碰到这个类，就决定了整条链路能不能用——而这不该取决于"第一个请求从哪条线程进来"。
     * 这里在构建期间显式把 TCCL 换成应用类加载器，把结果变成确定的：HTTP 线程、流式虚拟线程、
     * 以后的定时任务都一样能读到那个资源（虚拟线程允许改 TCCL，JDK 21 实测可行）。
     */
    private EmbeddingModel buildWithApplicationClassLoader(Supplier<EmbeddingModel> builder) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(EmbeddingClientFactory.class.getClassLoader());
            return builder.get();
        } finally {
            thread.setContextClassLoader(original);
        }
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

    /**
     * OpenAI 兼容协议：{@code /v1/embeddings}。
     *
     * <p>适配阿里云百炼 compatible-mode（{@code https://dashscope.aliyuncs.com/compatible-mode/v1}）、
     * DeepSeek、SiliconFlow、vLLM 等。{@code dimensions} 显式下发，避免远端默认值与知识库
     * 冻结维度不一致（百炼 text-embedding-v4 默认 1024，但显式传更安全）。
     */
    private EmbeddingModel buildOpenAiCompatibleEmbeddingModel(AiModelProperties.Provider provider,
                                                               AiModelProperties.ModelEntry entry) {
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .model(entry.getName());
        if (entry.getDimensions() != null && entry.getDimensions() > 0) {
            options.dimensions(entry.getDimensions());
        }
        if (provider.getHeaders() != null && !provider.getHeaders().isEmpty()) {
            options.customHeaders(provider.getHeaders());
        }
        return OpenAiEmbeddingModel.builder()
                .options(options.build())
                .build();
    }
}
