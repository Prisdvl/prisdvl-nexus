package com.lky.nexus.app.chat;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.agent.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>这条回答是哪个 Agent 跑的</b>——出处（{@code chat_message.agent_id}）在两条链路上的落地（Phase 5.4）。
 *
 * <p>为什么光有"字段存进去了"不够，要专门盯这一层：
 * <ol>
 *   <li>出处的值是<b>解析后</b>的具体 id，而不是会话上那个可能为 null 的 {@code agent_id}。
 *       这两者差了整整一个 {@code resolveDefault}：会话没选 Agent 时照抄 null，
 *       用户问"这是谁答的"就永远没有答案——而接口一切正常，没人会报 bug；</li>
 *   <li>非流式入口此前<b>完全不看会话模式</b>（P1-14 探索时发现的静默不一致）：
 *       AGENT 会话调它不用该 Agent 的提示词，RAG 会话调它不查笔记，两种都<b>不报错</b>。
 *       现在 AGENT 走同一份装配、RAG 显式报 8003，本类把这两条都钉住；</li>
 *   <li>用户消息<b>恒为 null</b>：给两行写同一个 id 会让"按 agent_id 聚合"把每轮对话重复计两次。</li>
 * </ol>
 *
 * <p>换掉 {@code ChatClientFactory} 是为了让"模型收到什么"成为已知输入：test profile 下没有
 * 真实 key，且这里要断言的本来也不是模型输出。桩模型同时捕获 Prompt，因此"非流式那条链路
 * 到底有没有用 Agent 的提示词"是直接看出来的，而不是靠推断。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Agent 出处：消息上的 agent_id 与两条链路的模式处理")
class ChatAgentProvenanceTest {

    private static final String PASSWORD = "Passw0rd!23";

    private static final String AGENT_PROMPT = "你是严格的笔记助手，只依据资料回答。";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentService agentService;

    /** 只是为了让下面那条 `configuredRoles()` 的桩取到真实的角色表，不参与断言。 */
    @Autowired
    private AiModelProperties modelProperties;

    @MockitoBean
    private ChatClientFactory chatClientFactory;

    private final List<Prompt> captured = new ArrayList<>();

    @BeforeEach
    void stubModel() {
        captured.clear();
        // 换掉工厂之后 **必须顺手把角色表透回去**：AgentService 的写入口校验走的正是这个接缝
        // （requireConfiguredModelRole → configuredRoles()），裸 mock 返回空集合，
        // 于是"建 Agent"这一步直接报 4006——测试会以"根本跑不到断言"的方式失败，
        // 而不是以一个说清了原因的断言失败。这条在别处不显眼，踩过一次就写在这儿。
        when(chatClientFactory.configuredRoles()).thenReturn(modelProperties.getRoles().keySet());
        ChatModel stub = mock(ChatModel.class);
        when(stub.getOptions()).thenReturn(ChatOptions.builder().build());
        when(stub.call(any(Prompt.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return response("这是一句回答。");
        });
        // 流式链路会走 stream(Prompt)。桩上必须显式顶掉它：ChatModel 的默认实现是
        // 直接抛 UnsupportedOperationException（"streaming is not supported"），
        // 不顶掉就变成"这条测试永远失败"而不是"某条断言失败"
        when(stub.stream(any(Prompt.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return Flux.just(response("这是一句"), response("回答。"));
        });
        when(chatClientFactory.get(anyString(), any())).thenReturn(ChatClient.builder(stub).build());
    }

    @Test
    @DisplayName("流式（AGENT 会话）：助手消息记下所选的 Agent，用户消息不记")
    void streamRecordsTheSelectedAgentOnAssistantMessageOnly() throws Exception {
        String token = registerAndLogin("provenance-stream-agent");
        String agentId = publishedAgent(token, "stream-agent", "流式用的 Agent");
        String conversationId = createConversation(token, "AGENT", agentId);

        stream(token, conversationId, "我的笔记里缓存击穿怎么解决？");

        List<Long> agentIds = agentIdsOf(token, conversationId);
        assertThat(agentIds)
                .as("两行消息：用户消息 null（不是 Agent 产的），助手消息是那个 Agent 的真实 id")
                .containsExactly(null, Long.valueOf(agentId));
        // 顺带把"AGENT 会话真的用了这个 Agent 的提示词"一起钉住：出处的值与提示词同源，
        // 只记 id 而没用提示词，等于给用户的答案贴了张错标签
        assertThat(systemTextSentToModel()).startsWith(AGENT_PROMPT);
    }

    @Test
    @DisplayName("流式（AGENT 会话没选 Agent）：助手消息记的是内置默认那一行的真实 id，不是 null")
    void streamRecordsBuiltinDefaultWhenConversationHasNoAgent() throws Exception {
        String token = registerAndLogin("provenance-stream-default");
        Long userId = currentUserId(token);
        String conversationId = createConversation(token, "AGENT", null);

        stream(token, conversationId, "缓存击穿怎么解决？");

        Long builtinId = agentService.resolveDefault(userId).agentId();
        assertThat(agentIdsOf(token, conversationId))
                .as("会话上写的是 null（= 跟随当前默认），消息上必须是当时真正在跑的那一行")
                .containsExactly(null, builtinId);
    }

    @Test
    @DisplayName("非流式（AGENT 会话）：同样用该 Agent 的提示词，同样记下出处")
    void syncPathAnswersWithTheAgentToo() throws Exception {
        String token = registerAndLogin("provenance-sync-agent");
        String agentId = publishedAgent(token, "sync-agent", "非流式用的 Agent");
        String conversationId = createConversation(token, "AGENT", agentId);

        mockMvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"缓存击穿怎么解决？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("这是一句回答。"));

        assertThat(agentIdsOf(token, conversationId)).containsExactly(null, Long.valueOf(agentId));
        // 这一条才是 5.4 修掉的东西：改之前它只读会话的 model_role 直接调模型，
        // 答案里没有这个 Agent 的提示词，也没有工具
        assertThat(systemTextSentToModel()).startsWith(AGENT_PROMPT);
    }

    @Test
    @DisplayName("非流式对 RAG 会话：报 8003 并指向流式入口，且不留下半条消息")
    void syncPathRefusesRagInsteadOfAnsweringWithoutNotes() throws Exception {
        String token = registerAndLogin("provenance-sync-rag");
        String kbId = createKnowledgeBase(token, "RAG 用的库");
        String conversationId = createConversation(token, "RAG", null, kbId);

        mockMvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"我的笔记里怎么解决缓存击穿？"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8003))
                // 报错要说清"该走哪个入口"：只说"不支持"的话，用户只能来问人
                .andExpect(jsonPath("$.message").value(containsString("/api/chat/stream")));

        // 校验在任何副作用之前：一条消息都不该多出来。否则用户看到的是
        // "我说了句话，它消失了"，而真正的原因（入口不对）已经过去了
        mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        assertThat(captured).as("拒绝之前不该调用模型（花钱且毫无意义）").isEmpty();
    }

    @Test
    @DisplayName("非流式（PLAIN 会话）：出处保持 null——别把默认 Agent 盖到普通对话上")
    void plainConversationKeepsNoProvenance() throws Exception {
        String token = registerAndLogin("provenance-plain");
        String conversationId = createConversation(token, "PLAIN", null);

        mockMvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"你好"}
                                """))
                .andExpect(status().isOk());

        assertThat(agentIdsOf(token, conversationId))
                .as("PLAIN 会话没有 Agent 参与，两行都该是 null")
                .containsExactly(null, null);
    }

    // ------------------------------------------------------------------ 辅助

    /** 该会话每条消息的 {@code agentId}，按时间正序（用户消息在前）。 */
    private List<Long> agentIdsOf(String token, String conversationId) throws Exception {
        String body = mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> raw = JsonPath.read(body, "$.data.list[*].agentId");
        return raw.stream().map(value -> value == null ? null : Long.valueOf(value.toString())).toList();
    }

    private String systemTextSentToModel() {
        assertThat(captured).as("模型应当被调用过").isNotEmpty();
        return captured.get(captured.size() - 1).getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.SYSTEM)
                .map(message -> ((SystemMessage) message).getText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("发给模型的 Prompt 里没有系统消息"));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model("stub-model")
                        .usage(new DefaultUsage(11, 22, 33))
                        .build());
    }

    /** 发起一次流式请求并等它跑完（SSE 是异步的，不等 async 结束就拿不到落库结果）。 */
    private void stream(String token, String conversationId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"conversationId":%s,"content":"%s"}
                                """.formatted(conversationId, content)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("流式响应里不该出现错误事件")
                .doesNotContain("event:error");
    }

    /** 建一个已发布的 Agent（草稿态不允许被会话选用，5008）。 */
    private String publishedAgent(String token, String code, String name) throws Exception {
        String created = mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s","systemPrompt":"%s"}
                                """.formatted(code, name, AGENT_PROMPT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String agentId = JsonPath.read(created, "$.data.id");

        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PUBLISHED"}
                                """))
                .andExpect(status().isOk());
        return agentId;
    }

    private String createConversation(String token, String mode, String agentId, String... kbIds) throws Exception {
        String kbPart = kbIds.length == 0 ? "" : ",\"kbIds\":[%s]".formatted(kbIds[0]);
        String agentPart = agentId == null ? "" : ",\"agentId\":%s".formatted(agentId);
        String body = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"出处测试","mode":"%s"%s%s}
                                """.formatted(mode, kbPart, agentPart)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private Long currentUserId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
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
