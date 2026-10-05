package com.lky.nexus.app.chat;

import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 删除会话必须把它的会话记忆一起带走。
 *
 * <p><b>为什么专门为它建一个测试类</b>：这条级联此前完全缺失 —— 会话被删了，
 * {@code SPRING_AI_CHAT_MEMORY} 里的 {@code conv:{id}} 还留着。实测生产库该表 415 行，
 * 其中大量属于早已删除的会话。它不会立刻炸，所以只能靠断言钉住形状。
 *
 * <p><b>为什么不调用模型</b>：直接用 {@link ChatMemory} 写入记忆，断言「删除是否真的清了它」。
 * 这样不需要 {@code AI_CHAT_API_KEY}，测试永远会跑 —— 一条只在本地配了 Key 才执行的断言，
 * 等于没有断言。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationDeleteClearsMemoryTest {

    /** 虚构用户 id：H2 无外键约束，本测试只关心「会话 → 记忆」这条级联。 */
    private static final Long USER_ID = 999101L;
    private static final Long OTHER_USER_ID = 999102L;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ChatMemory chatMemory;

    private Long newConversation() {
        return conversationService.create(USER_ID,
                new CreateConversationRequest("级联测试", null, null, null)).id();
    }

    private void remember(Long conversationId, String text) {
        chatMemory.add(ChatMemoryKeys.of(conversationId), List.of(new UserMessage(text)));
    }

    @Test
    @DisplayName("删除会话后，它的会话记忆必须为空")
    void deleteClearsConversationMemory() {
        Long conversationId = newConversation();
        remember(conversationId, "这句话在本会话被删之后不应继续存在");
        assertThat(chatMemory.get(ChatMemoryKeys.of(conversationId)))
                .as("前置：记忆已写入").hasSize(1);

        conversationService.delete(USER_ID, conversationId);

        assertThat(chatMemory.get(ChatMemoryKeys.of(conversationId)))
                .as("会话删了，记忆必须一起走 —— 否则它成为无主数据，"
                        + "还可能被后续同 id 的会话读到，表现为「新会话凭空记得旧会话说过的话」")
                .isEmpty();
    }

    @Test
    @DisplayName("只清自己那一条：删 A 不能动 B 的记忆（A/B 会话独立性）")
    void deleteDoesNotTouchOtherConversationsMemory() {
        Long a = newConversation();
        Long b = newConversation();
        remember(a, "A 的内容");
        remember(b, "B 的内容");

        conversationService.delete(USER_ID, a);

        assertThat(chatMemory.get(ChatMemoryKeys.of(a)))
                .as("A 已删，记忆应为空").isEmpty();
        assertThat(chatMemory.get(ChatMemoryKeys.of(b)))
                .as("B 没被删，记忆必须原样保留").hasSize(1);
        assertThat(chatMemory.get(ChatMemoryKeys.of(b)).get(0).getText())
                .as("保留的必须是 B 自己的内容，而不是被 A 的清理波及")
                .isEqualTo("B 的内容");
    }

    @Test
    @DisplayName("没有记忆的会话也能正常删除：清空一个空 key 是 no-op，不是错误")
    void deleteIsSafeForConversationWithoutMemory() {
        // 新建 → 一轮都没问过 → 记忆表里没有它的行。
        // 这是最常见的情形（PLAIN 会话根本不会写记忆），它绝不能因为「没东西可清」而失败。
        Long conversationId = newConversation();

        conversationService.delete(USER_ID, conversationId);

        assertThat(chatMemory.get(ChatMemoryKeys.of(conversationId)))
                .as("清一个本就不存在的 key 必须静默成功").isEmpty();
    }

    @Test
    @DisplayName("越权删除不产生副作用：别人的会话删不掉，记忆也不该被清")
    void foreignUserCannotDeleteNorClearMemory() {
        Long conversationId = newConversation();
        remember(conversationId, "属于 USER_ID 的记忆");

        assertThatThrownBy(() -> conversationService.delete(OTHER_USER_ID, conversationId))
                .as("删别人的会话必须失败").isInstanceOf(BusinessException.class);

        assertThat(chatMemory.get(ChatMemoryKeys.of(conversationId)))
                .as("归属校验必须发生在删除与清记忆之前 —— "
                        + "否则一次越权请求虽然报错，却已经产生了真实的破坏")
                .hasSize(1);
    }
}
