package com.lky.nexus.app.chat;

import com.lky.nexus.app.chat.model.dto.ChatAnswerResponse;
import com.lky.nexus.app.chat.model.dto.ConversationResponse;
import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.app.chat.service.ChatService;
import com.lky.nexus.app.chat.service.ConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 1.5 验收测试（真实调用部分）：多轮对话是否真的"记得住"。
 *
 * <p>为什么必须用真实模型：记忆机制（读历史、拼进请求）只有在模型真的看到历史时才成立。
 * 离线测试只能证明"仓库能存能取"，证明不了"历史被送进了模型"。
 *
 * <p>未设置 {@code AI_CHAT_API_KEY} 时整体跳过，不消耗 Token。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AI_CHAT_API_KEY", matches = ".+",
        disabledReason = "未配置 AI_CHAT_API_KEY，跳过多轮记忆真实调用")
class MultiTurnMemoryTest {

    private static final Logger log = LoggerFactory.getLogger(MultiTurnMemoryTest.class);

    /** 用一个"虚构"的用户 id：H2 无外键约束，本测试只关心记忆链路，不关心用户是否存在。 */
    private static final Long USER_ID = 999001L;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMemory chatMemory;

    @Test
    @DisplayName("第二轮能记住第一轮给出的事实（记忆真的进了模型）")
    void secondTurnRemembersWhatWasSaidInTheFirst() {
        ConversationResponse conversation = conversationService.create(
                USER_ID, new CreateConversationRequest("记忆测试", null, null, null));
        Long conversationId = conversation.id();

        ChatAnswerResponse first = chatService.ask(USER_ID, conversationId,
                "请记住一件事：我的名字是 LKY。收到只需回答\"好的\"。");
        assertThat(first.answer()).as("第一轮应有回答").isNotBlank();
        assertThat(first.totalTokens()).as("应记录 Token 用量").isNotNull().isPositive();

        // 记忆被"写"生效：顾问把本轮问答写回了仓库
        assertThat(chatMemory.get(ChatMemoryKeys.of(conversationId)))
                .as("本轮问答应已写入会话记忆")
                .isNotEmpty();

        ChatAnswerResponse second = chatService.ask(USER_ID, conversationId,
                "我的名字是什么？只回答名字本身，不要任何解释。");

        log.info("多轮记忆验证：第一轮回答={}，第二轮回答={}", first.answer(), second.answer());
        assertThat(second.answer())
                .as("第二轮应当从记忆里知道名字是 LKY")
                .containsIgnoringCase("LKY");

        // 完整历史（chat_message）应有 4 条：两轮 user + assistant
        assertThat(chatMessageService.listMessages(USER_ID, conversationId, 1, 20).total())
                .as("完整聊天历史应记录两轮问答")
                .isEqualTo(4L);
    }
}
