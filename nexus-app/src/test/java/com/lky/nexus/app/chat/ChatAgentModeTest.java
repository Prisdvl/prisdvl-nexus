package com.lky.nexus.app.chat;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.ChatMessageResponse;
import com.lky.nexus.app.chat.model.dto.MessageToolCall;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.common.api.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 模式的会话契约（离线部分）：模式落地 + 工具调用痕迹留档。
 *
 * <p>为什么要在离线层钉住这两件事：
 * <ol>
 *   <li><b>AGENT 模式真的存进去了</b>：会话表早有 MODE 列，但此前没有任何链路使用它，
 *       一旦"创建会话时传了 AGENT 却没落库"，表现会是"接口不报错、但回答从不检索知识库"，
 *       排查成本极高；</li>
 *   <li><b>工具痕迹能落库并原样读回</b>：痕迹存在 {@code chat_message.metadata}（JSON 文本），
 *       写进去只成功了一半——读出来解析失败时如果按空列表兜底，前端就会静默地什么都不显示。</li>
 * </ol>
 *
 * <p>真正"边跑边推"的 SSE 事件（tool_call / tool_result）需要真实模型，见
 * {@code ChatStreamLiveTest} 里被 {@code AI_CHAT_API_KEY} 门控的用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatAgentModeTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatMessageService chatMessageService;

    @Test
    @DisplayName("创建 AGENT 模式会话 → mode 落库并能读回")
    void agentModeIsPersisted() throws Exception {
        String token = registerAndLogin("agent-mode");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Agent 问答","mode":"agent"}
                                """))
                .andExpect(status().isOk())
                // 小写 agent 也应被宽松解析成 AGENT（ChatMode.parse）
                .andExpect(jsonPath("$.data.mode").value("AGENT"));
    }

    @Test
    @DisplayName("工具调用痕迹写入 metadata，并按 JSON 原样读回")
    void toolTraceIsPersistedAndReadBack() throws Exception {
        String token = registerAndLogin("agent-trace");
        Long userId = currentUserId(token);
        Long conversationId = createConversation(token, "AGENT");

        chatMessageService.appendUserMessage(userId, conversationId, "我的笔记里缓存击穿怎么解决？", "req-trace");
        ChatMessagePO assistant = chatMessageService.appendAssistantMessage(userId, conversationId,
                AssistantReplyDraft.builder()
                        .content("笔记里给了两种做法：SETNX 互斥锁与逻辑过期。")
                        .status("DONE")
                        .requestId("req-trace")
                        .toolCalls(List.of(
                                new MessageToolCall("knowledge_search", "缓存击穿", "3 条结果"),
                                new MessageToolCall("knowledge_search", "缓存击穿 解决方案", "知识库「我的 Java 笔记」精确检索不可用，已降级为关键词匹配（2 条）")))
                        .build());

        // ① 服务层读回
        PageResult<ChatMessageResponse> page =
                chatMessageService.listMessages(userId, conversationId, 1, 20);
        ChatMessageResponse readBack = page.list().stream()
                .filter(message -> message.id().equals(assistant.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(readBack.toolCalls())
                .as("工具痕迹必须能读回，且顺序、内容与写入一致")
                .hasSize(2)
                .extracting(MessageToolCall::tool, MessageToolCall::input, MessageToolCall::resultSummary)
                .containsExactly(
                        tuple("knowledge_search", "缓存击穿", "3 条结果"),
                        tuple("knowledge_search", "缓存击穿 解决方案",
                                "知识库「我的 Java 笔记」精确检索不可用，已降级为关键词匹配（2 条）"));

        // ② HTTP 层读回（前端拿到的形状）
        mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[1].toolCalls.length()").value(2))
                .andExpect(jsonPath("$.data.list[1].toolCalls[0].tool").value("knowledge_search"))
                .andExpect(jsonPath("$.data.list[0].toolCalls.length()")
                        .value(0));
    }

    @Test
    @DisplayName("普通对话（无工具）→ toolCalls 为空列表而不是 null（前端不必做空值判断）")
    void plainMessageHasEmptyToolCalls() throws Exception {
        String token = registerAndLogin("agent-plain");
        Long userId = currentUserId(token);
        Long conversationId = createConversation(token, "PLAIN");

        chatMessageService.appendUserMessage(userId, conversationId, "你好", "req-plain");
        chatMessageService.appendAssistantMessage(userId, conversationId,
                AssistantReplyDraft.builder().content("你好，有什么可以帮你？").status("DONE").build());

        PageResult<ChatMessageResponse> page =
                chatMessageService.listMessages(userId, conversationId, 1, 20);
        assertThat(page.list()).allSatisfy(message ->
                assertThat(message.toolCalls()).as("无工具场景返回空列表").isEmpty());
    }

    private Long currentUserId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private Long createConversation(String token, String mode) throws Exception {
        String body = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"%s"}
                                """.formatted(mode)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, PASSWORD)));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }
}
