package com.lky.nexus.app.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定回归保护：{@code nexus.chat.memory.max-messages} 必须真的从配置源绑到
 * 消费它的 {@link ChatMemory} Bean 上，而不是被 {@code @Value} 的默认值静默兜底。
 *
 * <p><b>为什么必须专门写这个测试（实测回归过）</b>：
 * {@code application.yml} 里的 {@code chat:} 曾因错误缩进，使
 * {@code nexus.chat.memory.max-messages} 在解析后的 Environment 里<b>根本不存在</b>——
 * 而 {@code ChatMemoryConfig.chatMemory(...)} 用的是
 * {@code @Value("${nexus.chat.memory.max-messages:20}")}，缺键时会安静地退回默认值 20，
 * 应用照常启动、测试照常通过。既有的 {@code ChatMemoryWindowingTest} 也<b>发现不了</b>：
 * 它的注入点写的是同一句 {@code @Value("${...:20}")}，同样带默认值，
 * 于是"配置缺失"和"配置=20"这两种情况在它眼里完全一样。
 *
 * <p><b>本类怎么让它响亮地失败</b>：
 * <ol>
 *   <li>断言 {@link Environment} 里<b>确实存在</b>该键（存在即说明有配置源提供了它；
 *       {@code @Value} 的默认值不会进 Environment，因此缺键时这里是 null）；</li>
 *   <li>断言 {@link ChatMemory} <b>按该键的值截断窗口</b>——把"配置值"和"组件实际行为"
 *       对上，而不是只看字符串。</li>
 * </ol>
 *
 * <p>本测试不调用模型、不需要 API Key、不依赖 Docker / Redis / RabbitMQ / ES：
 * 与 {@code ChatMemoryWindowingTest} 同一套 {@code test} profile（H2 + 内存安全实现）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("会话记忆窗口大小：配置键真的绑定到 ChatMemory（防止缩进错误被默认值掩盖）")
class ChatMemoryConfigBindingTest {

    /** 唯一由 application.yml 提供的键；本类刻意不带任何属性覆盖，就是为了验它本身。 */
    private static final String KEY = "nexus.chat.memory.max-messages";

    @Autowired
    private Environment environment;

    @Autowired
    private ChatMemory chatMemory;

    @Test
    @DisplayName("nexus.chat.memory.max-messages 由配置源提供，而不是靠 @Value 的默认值兜底")
    void propertyIsProvidedByConfigurationSource() {
        assertThat(environment.getProperty(KEY))
                .as("Environment 里找不到 %s —— 说明 application.yml 的 nexus.chat.memory 层级没被解析出来"
                        + "（典型症状就是 chat: 缩进错位）；此时 @Value 会静默用默认值，本断言负责让它显式失败", KEY)
                .isNotNull();
    }

    @Test
    @DisplayName("ChatMemory 实际采用配置的窗口上限：按上限截断，且保留最新一条")
    void chatMemoryHonoursConfiguredWindowSize() {
        int configured = Integer.parseInt(environment.getProperty(KEY));
        assertThat(configured).as("配置值必须是正数").isPositive();

        String conversationId = UUID.randomUUID().toString();
        int total = configured + 5;
        for (int i = 1; i <= total; i++) {
            chatMemory.add(conversationId, List.of(new UserMessage("问题 " + i)));
        }

        List<Message> messages = chatMemory.get(conversationId);
        assertThat(messages)
                .as("窗口必须严格等于配置上限 %d（写入了 %d 条）", configured, total)
                .hasSize(configured);
        assertThat(messages.get(messages.size() - 1).getText())
                .as("最新一条必须保留")
                .isEqualTo("问题 " + total);

        chatMemory.clear(conversationId);
    }
}