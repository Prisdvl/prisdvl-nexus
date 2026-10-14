package com.lky.nexus.app.chat;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.knowledge.rag.PreparedAnswer;
import com.lky.nexus.knowledge.rag.RagAnswer;
import com.lky.nexus.knowledge.rag.RagAnswerService;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFailure;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code retrieval} 事件（Phase 3.4）：两条流式链路的<b>事件顺序</b>与内容。
 *
 * <p><b>为什么这个顺序必须由测试钉住</b>：前端是靠"先收到 retrieval 再收到 delta"来决定
 * 什么时候显示"检索到 N 个片段"的。若 retrieval 跑到 delta 之后（哪怕只晚一拍），
 * 表现是"回答已经蹦字了，检索条数才突然冒出来"——看起来像界面抖动，
 * 而根因在服务端事件顺序，前端怎么改都是错的。所以断言写成"索引大小关系"，
 * 而不是"包含某个事件"。
 *
 * <p><b>为什么把 {@code RagAnswerService} 和 {@code ChatClientFactory} 都换成 mock</b>：
 * 本类要断言的是<b>事件编排</b>，不是检索质量、也不是模型输出。
 * test profile 下 ES 与 pgvector 都是 Disabled 实现（真检索必然报 4004/4005），
 * 真实模型更是要花钱且不确定；两者 mock 掉之后，"检索到几条"与"模型吐了几个字"
 * 都是已知输入，事件序列就成了唯一变量。
 *
 * <p>零资料的分支（{@code prepared.empty()}）是刻意覆盖的：它<b>不调用模型</b>，
 * 但仍然必须走完 retrieval → delta → done，否则前端会一直停在"检索中"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("RAG 流式：retrieval 事件的顺序与内容")
class RagStreamRetrievalEventTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagAnswerService ragAnswerService;

    @MockitoBean
    private ChatClientFactory chatClientFactory;

    @Test
    @DisplayName("会话流式（mode=RAG）：meta → retrieval → delta… → usage → done，且用的是知识库问答那套提示词")
    void chatStreamEmitsRetrievalBeforeFirstDelta() throws Exception {
        String token = registerAndLogin("rag-stream-chat");
        String kbId = createKnowledgeBase(token, "会话检索库");
        String conversationId = createRagConversation(token, kbId);

        PreparedAnswer prepared = preparedWithHits(kbId);
        when(ragAnswerService.prepareMany(any(), any(), anyString(), any(), any())).thenReturn(prepared);
        when(ragAnswerService.systemPrompt()).thenReturn("SYSTEM-PROMPT");
        when(ragAnswerService.userMessage(anyString(), any(), any())).thenReturn("USER-PROMPT");
        stubModel("缓存击穿", "可以用互斥锁。");

        String body = streamBody(token, "/api/chat/stream", """
                {"conversationId":%s,"content":"缓存击穿怎么解决？"}
                """.formatted(conversationId));

        assertThat(eventNames(body))
                .as("事件顺序：retrieval 必须在第一个 delta 之前")
                .containsSubsequence("meta", "retrieval", "delta", "usage", "done");
        assertThat(orderIn(body, "retrieval")).isLessThan(orderIn(body, "delta"));

        String retrieval = dataOf(body, "retrieval");
        assertThat(JsonPath.<Integer>read(retrieval, "$.count")).isEqualTo(2);
        assertThat(JsonPath.<String>read(retrieval, "$.hits[0].documentTitle")).isEqualTo("缓存击穿.md");
        assertThat(JsonPath.<String>read(retrieval, "$.hits[0].headingPath")).isEqualTo("Redis > 缓存 > 缓存击穿");
        // Phase 5.5：库里由单值改成列表，且每条命中各自带出处——否则界面上"检索到 2 个片段"
        // 无法回答"这两段分别来自哪个库"（会话可以绑多个库了）
        assertThat(JsonPath.<List<String>>read(retrieval, "$.knowledgeBaseIds")).containsExactly(kbId);
        assertThat(JsonPath.<String>read(retrieval, "$.hits[0].knowledgeBaseId")).isEqualTo(kbId);
        assertThat(JsonPath.<Boolean>read(retrieval, "$.empty")).isFalse();
        // 全部库都正常时给空列表而不是 null：前端只写一套渲染逻辑，不为"字段在不在"分叉
        // （5.5.1）
        assertThat(JsonPath.<List<?>>read(retrieval, "$.failedKnowledgeBases")).isEmpty();

        // 两条链路共用同一份提示词构造：这里断言会话流式确实走的是 RagAnswerService 的方法，
        // 而不是自己拼了一份 —— 否则同一个人问同一个问题会得到两种答案，且很难发现
        verify(ragAnswerService).userMessage(eq("缓存击穿怎么解决？"), eq(prepared), any());
        assertThat(body).as("delta 里应是模型吐出的内容").contains("互斥锁");
    }

    @Test
    @DisplayName("知识库问答流式：同样先发 retrieval（前端一套解析逻辑）")
    void askStreamEmitsRetrievalAsWell() throws Exception {
        String token = registerAndLogin("rag-stream-ask");
        String kbId = createKnowledgeBase(token, "问答检索库");

        // /ask/stream 走的是单库 prepare（路径里带 {id}），这里刻意用单库的桩：
        // 它证明"知识库问答流式这条路没被多库改造带偏"，会话流式那条才走 prepareMany
        when(ragAnswerService.prepare(any(), any(), anyString(), any(), any()))
                .thenReturn(preparedWithHits(kbId));
        when(ragAnswerService.systemPrompt()).thenReturn("SYSTEM-PROMPT");
        when(ragAnswerService.userMessage(anyString(), any(), any())).thenReturn("USER-PROMPT");
        // 留档也要给个真值：mock 默认返回 null，而这条链路结束后会读 recordId / citations，
        // 空值会在"结束时"炸掉（表现为 emitter 永不 complete、测试等到超时），
        // 而不是在断言处失败——所以宁可显式造一条留档记录。
        when(ragAnswerService.recordAnswer(any(), any(), anyString(), anyString(), any(), anyInt(), anyInt(),
                anyBoolean(), anyLong())).thenReturn(recorded());
        stubModel("互斥锁", "与逻辑过期。");

        String body = streamBody(token, "/api/knowledge-bases/" + kbId + "/ask/stream", """
                {"question":"缓存击穿怎么解决？"}
                """);

        assertThat(eventNames(body)).containsSubsequence("meta", "retrieval", "delta", "done");
        assertThat(orderIn(body, "retrieval")).isLessThan(orderIn(body, "delta"));
        assertThat(JsonPath.<Integer>read(dataOf(body, "retrieval"), "$.count")).isEqualTo(2);
        // 单库链路也发列表（一个元素），两条链路的事件形状完全一致：
        // 形状分叉意味着前端要写两套解析，而"两份里只改了一份"是这类问题的经典来源
        assertThat(JsonPath.<List<String>>read(dataOf(body, "retrieval"), "$.knowledgeBaseIds"))
                .containsExactly(kbId);
    }

    @Test
    @DisplayName("零资料：retrieval(count=0) 照发，且不调用模型——前端不能停在\"检索中\"")
    void emptyRetrievalIsStillAnnouncedWithoutCallingTheModel() throws Exception {
        String token = registerAndLogin("rag-stream-empty");
        String kbId = createKnowledgeBase(token, "空库");
        String conversationId = createRagConversation(token, kbId);

        when(ragAnswerService.prepareMany(any(), any(), anyString(), any(), any()))
                .thenReturn(PreparedAnswer.none());
        ChatClient.StreamResponseSpec streamSpec = stubModel("x", "y");

        String body = streamBody(token, "/api/chat/stream", """
                {"conversationId":%s,"content":"库里没有的问题"}
                """.formatted(conversationId));

        assertThat(eventNames(body)).containsSubsequence("meta", "retrieval", "delta", "done");
        String retrieval = dataOf(body, "retrieval");
        assertThat(JsonPath.<Integer>read(retrieval, "$.count")).isZero();
        assertThat(JsonPath.<Boolean>read(retrieval, "$.empty")).isTrue();
        assertThat(JsonPath.<List<?>>read(retrieval, "$.hits")).isEmpty();

        // 没有资料就不调模型：既省钱，也从根上避免"无资料硬答"
        verify(streamSpec, never()).chatResponse();
        assertThat(body).as("仍要给出一句明确的回答，而不是空白").contains(RagAnswerService.EMPTY_ANSWER);
        assertThat(JsonPath.<String>read(dataOf(body, "done"), "$.finishReason")).isEqualTo("empty");
    }

    @Test
    @DisplayName("有库检索失败：retrieval 事件里带上失败明细，且『没有资料』的回答换一种说法")
    void failedKnowledgeBasesAreAnnouncedAndChangeTheEmptyAnswer() throws Exception {
        String token = registerAndLogin("rag-stream-kb-failed");
        String kbA = createKnowledgeBase(token, "正常库");
        String kbB = createKnowledgeBase(token, "故障库");
        String conversationId = createRagConversation(token, kbA, kbB);

        // 一个库失败、另一个库也没有命中：这是最容易说假话的那一格——
        // 没有 failures 时它会被报成"资料中没有相关内容（知识库尚未导入或未建立索引）"，
        // 而真相是有个库压根没被搜过
        when(ragAnswerService.prepareMany(any(), any(), anyString(), any(), any()))
                .thenReturn(PreparedAnswer.none(List.of(new RetrievalFailure(
                        Long.valueOf(kbB), "故障库", "ES 连接被拒绝"))));
        ChatClient.StreamResponseSpec streamSpec = stubModel("x", "y");

        String body = streamBody(token, "/api/chat/stream", """
                {"conversationId":%s,"content":"任意问题"}
                """.formatted(conversationId));

        String retrieval = dataOf(body, "retrieval");
        assertThat(JsonPath.<String>read(retrieval, "$.failedKnowledgeBases[0].knowledgeBaseName"))
                .isEqualTo("故障库");
        assertThat(JsonPath.<String>read(retrieval, "$.failedKnowledgeBases[0].knowledgeBaseId"))
                .isEqualTo(kbB);
        assertThat(JsonPath.<String>read(retrieval, "$.failedKnowledgeBases[0].reason"))
                .contains("ES 连接被拒绝");
        // 全部正常时也要给一个空列表（不是 null）：前端的 forEach 不该需要判空
        assertThat(JsonPath.<List<?>>read(retrieval, "$.failedKnowledgeBases")).hasSize(1);

        verify(streamSpec, never()).chatResponse();
        assertThat(body)
                .as("『有库没搜成』不能被说成『资料中没有』——后者是一个与事实不符的断言")
                .contains(RagAnswerService.RETRIEVAL_FAILED_ANSWER)
                .doesNotContain(RagAnswerService.EMPTY_ANSWER);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 命中两条、已重排的检索结果。
     *
     * @param kbId 命中项的来源库——生产路径上这个值由检索层填（Phase 5.5），
     *             桩里也必须填真值，否则"每条命中带不带出处"这条断言等于没测
     */
    private PreparedAnswer preparedWithHits(String kbId) {
        return new PreparedAnswer("【1】互斥锁方案……\n【2】逻辑过期方案……",
                List.of(hit(Long.valueOf(kbId), 20001L, 100L, "缓存击穿.md", "Redis > 缓存 > 缓存击穿"),
                        hit(Long.valueOf(kbId), 20002L, 100L, "缓存击穿.md", "Redis > 缓存 > 逻辑过期")),
                1200, 0, true, false);
    }

    /** 留档结果（流式结束时读它的 recordId 与 citations）。 */
    private RagAnswer recorded() {
        return new RagAnswer(9001L, "互斥锁与逻辑过期。", List.of(), 1200, 0, true, 3000L);
    }

    private ChunkSearchService.SearchHit hit(Long kbId, Long chunkId, Long documentId, String title,
                                             String heading) {
        return new ChunkSearchService.SearchHit(chunkId, documentId, title, heading,
                "互斥锁方案的正文……", 0.83, "hybrid", List.of("BM25 #1", "向量 #2"), kbId);
    }

    /** 把模型换成"吐指定文字"的假客户端，返回流式规范以便断言"到底调没调模型"。 */
    private ChatClient.StreamResponseSpec stubModel(String first, String second) {
        ChatClient chatClient = org.mockito.Mockito.mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = org.mockito.Mockito.mock(ChatClient.StreamResponseSpec.class);
        when(chatClientFactory.get(anyString(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        /*
         * 5.6：生产代码在这条链上多加了一次 `.advisors(...)`（把 userId 交给记账顾问）。
         * 桩是一条"每步都返回自己"的链，必须跟着补上这一步——否则 Mockito 对未打桩的
         * 返回对象给 null，NPE 会发生在 `.stream()` 上（报的是"advisors 返回了 null"，
         * 看起来像生产代码写错了，其实是桩没跟上）。
         */
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chunk(first), chunk(second)));
        return streamSpec;
    }

    private ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 发起一次 SSE 请求并拿到完整响应体（SSE 是异步的，必须等 async 结束再取）。 */
    private String streamBody(String token, String path, String json) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(json))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> eventNames(String body) {
        return body.lines()
                .filter(line -> line.startsWith("event:"))
                .map(line -> line.substring("event:".length()).trim())
                .toList();
    }

    private int orderIn(String body, String event) {
        int index = body.indexOf("event:" + event);
        assertThat(index).as("响应体里没有 %s 事件：\n%s", event, body).isNotNegative();
        return index;
    }

    private String dataOf(String body, String event) {
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith("event:") && lines[i].substring("event:".length()).trim().equals(event)
                    && lines[i + 1].startsWith("data:")) {
                return lines[i + 1].substring("data:".length()).trim();
            }
        }
        throw new AssertionError("响应体里没有 " + event + " 事件的数据：\n" + body);
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.id");
    }

    /** 建一个 RAG 会话；可变参数是为了能绑多个库（多库失败那条用例需要）。 */
    private String createRagConversation(String token, String... kbIds) throws Exception {
        String ids = String.join(",", List.of(kbIds));
        String response = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"RAG 会话","mode":"RAG","kbIds":[%s]}
                                """.formatted(ids)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.id");
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
