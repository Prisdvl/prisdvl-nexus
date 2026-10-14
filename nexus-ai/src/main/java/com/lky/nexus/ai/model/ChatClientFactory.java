package com.lky.nexus.ai.model;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.common.security.ChatUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 工厂：业务代码通过<b>逻辑角色</b>获取 {@link ChatClient}（ADR-003）。
 *
 * <p>业务侧的写法只有这一种：
 * <pre>{@code
 * ChatClient client = chatClientFactory.get(AiModelProperties.Role.CHAT_AGENT);
 * }</pre>
 * 任何业务类里都不允许出现 {@code OpenAiChatModel} / {@code OllamaChatModel} 字样
 * （Phase 11 会用 ArchUnit 断言这一点）。
 *
 * <p>角色到模型的映射在 {@code ai.roles} 里，因此：
 * <ul>
 *   <li>把 {@code chat.judge} 换成更便宜的模型 → 改 yaml；</li>
 *   <li>对话从 DeepSeek 切到 Ollama → 改 yaml；</li>
 *   <li>业务代码、测试、评测代码全部不动。</li>
 * </ul>
 *
 * <p>ChatClient 是线程安全的轻量对象，可以缓存复用；这里用
 * {@link ConcurrentHashMap#computeIfAbsent} 做惰性构建，避免启动时就要求所有角色都可用。
 *
 * <p>（5.6）每个 client 都挂上 {@link UsageMeteringAdvisor}：这是 token 记账的<b>唯一收口</b>，
 * 六个真实发起模型调用的入口全都经过本工厂，所以挂在这里一处即可全覆盖。
 * 代价是"装配期就依赖 {@link ChatUsageRecorder}"——因此 recorder 允许缺省：
 * 不传就是 {@link ChatUsageRecorder#NOOP}（纯 AI 层的单测不需要配额）。
 */
public class ChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatClientFactory.class);

    private final AiModelProperties properties;
    private final AiModelRegistry registry;
    private final ChatUsageRecorder usageRecorder;
    private final ObservabilityRegistry observability;
    private final Map<String, ChatClient> clientsByRole = new ConcurrentHashMap<>();
    /**
     * 用户自定义凭据查询端口；纯 AI 层 / 无此 bean 时为 {@code null}，全部走服务端全局模型。
     */
    private final UserChatCredentialsProvider credentialsProvider;
    /**
     * 用户级 client 缓存：key 是"用户 + 凭据指纹"。凭据一变指纹就变 → 自动重建；
     * 旧条目留在内存里不清理（个人系统，量级可控），避免跨组件做失效通知。
     */
    private final Map<String, ChatClient> clientsByUser = new ConcurrentHashMap<>();

    /** 不记账的构造：给不关心配额的测试与纯 AI 层使用。 */
    public ChatClientFactory(AiModelProperties properties, AiModelRegistry registry) {
        this(properties, registry, ChatUsageRecorder.NOOP);
    }

    /** 不记指标的构造：模型调用次数/耗时/Token 不上报（纯 AI 层单测用它）。 */
    public ChatClientFactory(AiModelProperties properties, AiModelRegistry registry,
                             ChatUsageRecorder usageRecorder) {
        this(properties, registry, usageRecorder, new ObservabilityRegistry());
    }

    public ChatClientFactory(AiModelProperties properties, AiModelRegistry registry,
                             ChatUsageRecorder usageRecorder, ObservabilityRegistry observability) {
        this(properties, registry, usageRecorder, observability, null);
    }

    /** 全参构造（装配用）：带上用户自定义凭据端口。 */
    public ChatClientFactory(AiModelProperties properties, AiModelRegistry registry,
                             ChatUsageRecorder usageRecorder, ObservabilityRegistry observability,
                             UserChatCredentialsProvider credentialsProvider) {
        this.properties = properties;
        this.registry = registry;
        this.usageRecorder = usageRecorder == null ? ChatUsageRecorder.NOOP : usageRecorder;
        this.observability = observability == null ? new ObservabilityRegistry() : observability;
        this.credentialsProvider = credentialsProvider;
    }

    /**
     * 按逻辑角色取 ChatClient（服务端全局模型，不带用户上下文）。
     *
     * @param role 角色名，建议使用 {@link AiModelProperties.Role} 中的常量
     * @throws BusinessException 4006：角色未配置，或角色指向的模型未注册（例如 API Key 缺失）
     */
    public ChatClient get(String role) {
        return clientsByRole.computeIfAbsent(role, this::create);
    }

    /**
     * 按逻辑角色取 ChatClient，并优先使用<b>该用户自己配置的</b>对话模型。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code userId} 为空、没有凭据端口、或该用户没启用自定义配置 → 回落 {@link #get(String)}</li>
     *   <li>用户已启用并填齐 base-url / api-key / 模型名 → 用他自己的凭据构建（或命中缓存），
     *       此时忽略 {@code role} 指向的全局模型——"我配了自己的模型，就该全程用它"。</li>
     * </ul>
     *
     * <p>设计取舍：自定义凭据只替换<b>对话生成</b>，不替换嵌入与重排（重排是平台为检索质量
     * 自己花的钱，见 {@code LlmReranker} 的 SYSTEM_CALL 注释）。
     */
    public ChatClient get(String role, Long userId) {
        if (userId == null || credentialsProvider == null) {
            return get(role);
        }
        Optional<UserChatCredentials> credentials = credentialsProvider.find(userId);
        if (credentials.isEmpty()) {
            return get(role);
        }
        UserChatCredentials creds = credentials.get();
        String cacheKey = userId + "|" + creds.baseUrl() + "|" + creds.apiKey() + "|" + creds.model();
        return clientsByUser.computeIfAbsent(cacheKey, key -> createWithUserCredentials(creds));
    }

    private ChatClient createWithUserCredentials(UserChatCredentials creds) {
        ChatModel model = registry.buildOpenAiChatModel(creds.baseUrl(), creds.apiKey(), creds.model());
        log.info("使用用户自定义对话模型：baseUrl={}, model={}", creds.baseUrl(), creds.model());
        return ChatClient.builder(model)
                .defaultAdvisors(new UsageMeteringAdvisor(usageRecorder, observability))
                .build();
    }

    /** 已配置的角色集合。 */
    public Set<String> configuredRoles() {
        return properties.getRoles().keySet();
    }

    private ChatClient create(String role) {
        String reference = properties.getRoles().get(role);
        if (!StringUtils.hasText(reference)) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "逻辑角色 %s 未在 ai.roles 中配置。已配置：%s".formatted(role, configuredRoles()));
        }
        log.debug("为角色 {} 构建 ChatClient，模型引用={}", role, reference);
        return ChatClient.builder(registry.requireChatModel(reference))
                // 记账顾问在这里挂一次，六个调用点一起覆盖（见类注释）。
                // defaultAdvisors 与业务代码在请求上追加的 advisors 是**合并**关系
                // （DefaultChatClient.prompt() 复制的是含默认顾问的模板 spec，链按 order 排序），
                // 所以聊天记忆顾问与本顾问可以共存，互不挤掉对方
                .defaultAdvisors(new UsageMeteringAdvisor(usageRecorder, observability))
                .build();
    }
}
