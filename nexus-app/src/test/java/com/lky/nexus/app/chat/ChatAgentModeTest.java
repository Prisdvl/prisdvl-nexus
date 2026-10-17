package com.lky.nexus.app.chat;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.ChatMessageResponse;
import com.lky.nexus.app.chat.model.dto.MessageToolCall;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.knowledge.rag.RagAnswer;
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
        // agentId 传 null：本用例钉的是"工具痕迹能不能原样往返"，出处那一列另有
        // ChatAgentProvenanceTest 专门盯（那条链路才真的会解析 Agent）
        ChatMessagePO assistant = chatMessageService.appendAssistantMessage(userId, conversationId, null,
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
        chatMessageService.appendAssistantMessage(userId, conversationId, null,
                AssistantReplyDraft.builder().content("你好，有什么可以帮你？").status("DONE").build());

        PageResult<ChatMessageResponse> page =
                chatMessageService.listMessages(userId, conversationId, 1, 20);
        assertThat(page.list()).allSatisfy(message -> {
            assertThat(message.toolCalls()).as("无工具场景返回空列表").isEmpty();
            assertThat(message.citations()).as("无检索场景返回空列表").isEmpty();
        });
    }

    /**
     * 引用随消息落库（update.txt §五）。
     *
     * <p>钉住三件事，缺一件这个功能就等于没做：
     * <ol>
     *   <li><b>引用读得回来</b>——检索事件只在流里发一次，不落库就"刷新即消失"；</li>
     *   <li><b>与工具痕迹共存</b>——两者共用 {@code metadata} 一列，写其中一个时
     *       把另一个抹掉，症状是"有引用了但过程看不见了"（或反过来），
     *       而这两种表现都像是渲染问题，不会有人去怀疑序列化；</li>
     *   <li><b>多库出处与"是否被引用"逐字段保真</b>——它们是多库检索下
     *       "这段资料哪来的、模型到底用没用" 的唯一答案。</li>
     * </ol>
     */
    @Test
    @DisplayName("引用与工具痕迹共存在 metadata 里，并按 JSON 逐字段读回")
    void citationsAndToolTraceShareMetadataWithoutClobberingEachOther() throws Exception {
        String token = registerAndLogin("agent-citation");
        Long userId = currentUserId(token);
        Long conversationId = createConversation(token, "AGENT");

        chatMessageService.appendUserMessage(userId, conversationId, "两个库里的缓存击穿都怎么说的？", "req-cite");
        ChatMessagePO assistant = chatMessageService.appendAssistantMessage(userId, conversationId, null,
                AssistantReplyDraft.builder()
                        .content("两个库给了互补的做法[1]，另见[2]。")
                        .status("DONE")
                        .requestId("req-cite")
                        .toolCalls(List.of(new MessageToolCall("knowledge_search", "缓存击穿", "2 条结果")))
                        .citations(List.of(
                                new RagAnswer.RagCitation(1, 1001L, 9001L, 501L, "Java 笔记", "缓存 › 击穿",
                                        "用互斥锁重建，只让一个请求打到库上。", 0.91, true),
                                new RagAnswer.RagCitation(2, 1002L, 9002L, 502L, "运维手册", "故障处置 › 缓存雪崩",
                                        "雪崩与击穿的区别在于大量 key 同时失效。", 0.83, true)))
                        .build());

        PageResult<ChatMessageResponse> page =
                chatMessageService.listMessages(userId, conversationId, 1, 20);
        ChatMessageResponse readBack = page.list().stream()
                .filter(message -> message.id().equals(assistant.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(readBack.citations())
                .as("引用必须能读回，且编号、出处、正文、分数、被引用标记都不能丢")
                .hasSize(2)
                .extracting(RagAnswer.RagCitation::index, RagAnswer.RagCitation::knowledgeBaseId,
                        RagAnswer.RagCitation::chunkId, RagAnswer.RagCitation::documentId,
                        RagAnswer.RagCitation::documentTitle, RagAnswer.RagCitation::headingPath,
                        RagAnswer.RagCitation::score, RagAnswer.RagCitation::referenced)
                .containsExactly(
                        tuple(1, 1001L, 9001L, 501L, "Java 笔记", "缓存 › 击穿", 0.91, true),
                        tuple(2, 1002L, 9002L, 502L, "运维手册", "故障处置 › 缓存雪崩", 0.83, true));
        assertThat(readBack.citations().get(0).snippet()).isEqualTo("用互斥锁重建，只让一个请求打到库上。");
        assertThat(readBack.toolCalls())
                .as("写引用不能把共用同一列的工具痕迹抹掉")
                .hasSize(1)
                .extracting(MessageToolCall::tool, MessageToolCall::resultSummary)
                .containsExactly(tuple("knowledge_search", "2 条结果"));

        // HTTP 层：前端拿到的形状（多库出处 knowledgeBaseId 必须出现在 JSON 里）
        mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[1].citations.length()").value(2))
                .andExpect(jsonPath("$.data.list[1].citations[0].index").value(1))
                .andExpect(jsonPath("$.data.list[1].citations[0].knowledgeBaseId").value(1001))
                .andExpect(jsonPath("$.data.list[1].citations[1].documentTitle").value("运维手册"))
                .andExpect(jsonPath("$.data.list[1].citations[1].referenced").value(true))
                .andExpect(jsonPath("$.data.list[1].toolCalls.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].citations.length()").value(0));
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
                        {"username":"%s","password":"%s","email":"%s@example.com"}
                        """.formatted(username, PASSWORD, username)));

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
