package com.lky.prisma.ai.model;

import com.lky.prisma.ai.config.AiModelProperties;
import com.lky.prisma.common.api.ErrorCode;
import com.lky.prisma.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

import java.util.Map;
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
 */
public class ChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatClientFactory.class);

    private final AiModelProperties properties;
    private final AiModelRegistry registry;
    private final Map<String, ChatClient> clientsByRole = new ConcurrentHashMap<>();

    public ChatClientFactory(AiModelProperties properties, AiModelRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * 按逻辑角色取 ChatClient。
     *
     * @param role 角色名，建议使用 {@link AiModelProperties.Role} 中的常量
     * @throws BusinessException 4006：角色未配置，或角色指向的模型未注册（例如 API Key 缺失）
     */
    public ChatClient get(String role) {
        return clientsByRole.computeIfAbsent(role, this::create);
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
        return ChatClient.builder(registry.requireChatModel(reference)).build();
    }
}
