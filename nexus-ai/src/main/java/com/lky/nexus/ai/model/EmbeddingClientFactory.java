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

import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>两条取客户端的路径：{@link #get(String)} 用服务端全局配置（按角色），
 * {@link #getForUser(UserEmbeddingCredentials)} 用用户自带凭据（BYOK：白名单 + 固定维度）。
 * 二者共用同一段构建逻辑，不存在"两份实现各自漂移"的余地。
 */
@Component
public class EmbeddingClientFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClientFactory.class);

    private final AiModelProperties properties;
    private final EmbeddingByokPolicy byokPolicy;
    private final Map<String, EmbeddingClient> clientsByRole = new ConcurrentHashMap<>();
    /**
     * 用户自带凭据的客户端缓存：key 是"凭据指纹"（含 provider/model/baseUrl/apiKey/dimensions）。
     * 凭据一变指纹就变 → 自动重建；旧条目留在内存里不清理（与 {@link ChatClientFactory}
     * 同一套取舍：个人系统量级可控，避免跨组件做失效通知）。
     */
    private final Map<String, EmbeddingClient> clientsByUser = new ConcurrentHashMap<>();

    /**
     * 唯一的构造器（构造器注入由 Spring 完成）。
     *
     * <p>刻意<b>不</b>再提供 {@code (AiModelProperties)} 这个便捷重载：本类是 {@code @Component}，
     * 一个类有多个未被 {@code @Autowired} 标注的构造器、又没有无参构造器时，
     * Spring 无法决定用哪个，会直接 {@code BeanInstantiationException} 启动失败。
     * （{@code ChatClientFactory} 之所以能有多个构造器，是因为它由
     * {@code AiConfiguration} 的 {@code @Bean} 方法手工装配，不走构造器推断。）
     */
    public EmbeddingClientFactory(AiModelProperties properties, EmbeddingByokPolicy byokPolicy) {
        this.properties = properties;
        this.byokPolicy = byokPolicy;
    }

    /**
     * 按逻辑角色取嵌入客户端（线程安全，按角色缓存）。
     *
     * @throws BusinessException 4006：角色未配置 / 模型不存在 / 未声明 EMBEDDING 能力 / 协议暂不支持
     */
    public EmbeddingClient get(String role) {
        return clientsByRole.computeIfAbsent(role, this::create);
    }

    /**
     * 取<b>用户自带凭据</b>的嵌入客户端（BYOK 路线 A）。
     *
     * <p>与全局路径的区别只有"配置从哪来"：协议、维度下发、类加载器处理全部复用同一段构建逻辑，
     * 所以用户路径与全局路径在行为上不存在第二套实现。
     *
     * <p>协议按 {@code ai.providers} 里同名供应商的 {@code type} 决定（找不到就按
     * OpenAI 兼容处理），附加请求头也从同名全局供应商继承——这样"同一网关、不同 Key"
     * 的行为与全局配置一致，不会因为少发一个自定义头而在某些网关上报 401。
     *
     * <p><b>白名单与维度在这里再校验一次</b>：解析方（{@code KnowledgeEmbeddingResolver}）
     * 已经查过，但这里是"向量可能被真正生产出来"的最后一道边界，多一道断言代价极低，
     * 却能杜绝将来某个新调用点绕过策略直接建客户端。
     *
     * @throws BusinessException 4006：白名单外的模型，或维度不是平台允许的维度
     */
    public EmbeddingClient getForUser(UserEmbeddingCredentials credentials) {
        byokPolicy.requireAllowed(credentials.provider(), credentials.model(),
                credentials.dimensions(), ErrorCode.MODEL_ROLE_NOT_CONFIGURED);
        String cacheKey = "user|" + credentials.fingerprint();
        return clientsByUser.computeIfAbsent(cacheKey, key -> createForUser(credentials));
    }

    private EmbeddingClient createForUser(UserEmbeddingCredentials credentials) {
        AiModelProperties.Provider provider = new AiModelProperties.Provider();
        provider.setName(credentials.provider());
        provider.setType(protocolOf(credentials.provider()));
        provider.setBaseUrl(credentials.baseUrl());
        provider.setApiKey(credentials.apiKey());
        // 同名全局供应商的附加请求头对用户路径同样适用（见方法注释）
        properties.findProvider(credentials.provider())
                .ifPresent(global -> provider.setHeaders(global.getHeaders()));

        AiModelProperties.ModelEntry entry = new AiModelProperties.ModelEntry();
        entry.setName(credentials.model());
        entry.setDimensions(credentials.dimensions());
        entry.setCapabilities(new ArrayList<>(List.of(AiModelProperties.ModelCapability.EMBEDDING)));

        EmbeddingModel model = buildWithApplicationClassLoader(() -> switch (provider.getType()) {
            case OLLAMA -> buildOllamaEmbeddingModel(provider, entry);
            case OPENAI_COMPATIBLE -> buildOpenAiCompatibleEmbeddingModel(provider, entry);
        });

        // 只打模型引用与端点：apiKey 绝不进日志（与 ChatClientFactory 同一种口径）
        log.info("使用用户自带的嵌入模型：{}（端点 {}，声明维度 {}）",
                credentials.reference(), credentials.baseUrl(), credentials.dimensions());
        return new EmbeddingClient(credentials.reference(), model, credentials.dimensions());
    }

    private AiModelProperties.ProviderType protocolOf(String providerName) {
        return properties.findProvider(providerName)
                .map(AiModelProperties.Provider::getType)
                .orElse(AiModelProperties.ProviderType.OPENAI_COMPATIBLE);
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
