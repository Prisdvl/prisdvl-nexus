package com.lky.nexus.app.chat;

import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 1.5 验收测试（离线部分）：会话记忆的装配与窗口行为。
 *
 * <p>本测试<b>不调用模型</b>，因此不需要 API Key：它验证的是"记忆机制本身"——
 * 仓库确实是 JDBC 实现、写入后能读回、窗口超限时淘汰最老的消息、key 不会超出列宽。
 * "模型是否真的记住了上一轮"由 {@code MultiTurnMemoryTest} 在真实调用下验证。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatMemoryWindowingTest {

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ChatMemoryRepository chatMemoryRepository;

    @Value("${nexus.chat.memory.max-messages:20}")
    private int maxMessages;

    @Test
    @DisplayName("记忆仓库是 JDBC 实现（而不是默认的内存实现）")
    void memoryIsBackedByJdbcRepository() {
        // 这条断言若失败，说明 starter 没生效或自动配置退回了 InMemoryChatMemoryRepository，
        // 那样重启应用就会丢失上下文。
        assertThat(chatMemoryRepository).isInstanceOf(JdbcChatMemoryRepository.class);
    }

    @Test
    @DisplayName("写入后可读回，且按时间顺序返回")
    void messagesCanBeWrittenAndReadBack() {
        // 直接用 UUID 作 key（36 字符，正好等于 SPRING_AI_CHAT_MEMORY.conversation_id 的列宽）
        String conversationId = UUID.randomUUID().toString();

        chatMemory.add(conversationId, List.of(new UserMessage("第一句")));
        chatMemory.add(conversationId, List.of(new UserMessage("第二句")));

        List<Message> messages = chatMemory.get(conversationId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText()).isEqualTo("第一句");
        assertThat(messages.get(1).getText()).isEqualTo("第二句");

        chatMemory.clear(conversationId);
        assertThat(chatMemory.get(conversationId)).isEmpty();
    }

    @Test
    @DisplayName("窗口超限时淘汰最老的消息（最新一条必须保留）")
    void windowEvictsOldestMessages() {
        String conversationId = UUID.randomUUID().toString();
        int total = maxMessages + 10;
        for (int i = 1; i <= total; i++) {
            chatMemory.add(conversationId, List.of(new UserMessage("问题 " + i)));
        }

        List<Message> messages = chatMemory.get(conversationId);

        assertThat(messages).as("窗口不应超过配置上限").hasSizeLessThanOrEqualTo(maxMessages);
        assertThat(messages.get(messages.size() - 1).getText())
                .as("最新一条必须保留").isEqualTo("问题 " + total);
        assertThat(messages).as("最老的一条应被淘汰")
                .noneMatch(message -> "问题 1".equals(message.getText()));

        chatMemory.clear(conversationId);
    }

    @Test
    @DisplayName("记忆 key 放得进列宽：即使是最长的雪花 ID（19 位）也远小于 36")
    void memoryKeyFitsColumnWidth() {
        String key = ChatMemoryKeys.of(2104750903666438145L);
        assertThat(key).isEqualTo("conv:2104750903666438145");

        // 用 Long.MAX_VALUE 构造最坏情况：19 位数字
        String worstCase = ChatMemoryKeys.of(Long.MAX_VALUE);
        assertThat(worstCase).isEqualTo("conv:9223372036854775807");
        assertThat(worstCase.length())
                .as("key 必须放得进 SPRING_AI_CHAT_MEMORY.conversation_id (VARCHAR(%d))",
                        ChatMemoryKeys.MAX_KEY_LENGTH)
                .isLessThanOrEqualTo(ChatMemoryKeys.MAX_KEY_LENGTH);
    }
}
