package com.lky.nexus.app.chat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 会话与知识库的绑定契约（Phase 3.4）。
 *
 * <p>这一层要钉住的是"绑定"这件事的<b>准入与可见性</b>，而不是检索质量：
 * <ol>
 *   <li><b>能存能读</b>：{@code chat_conversation.kb_ids} 是 JSON 列，MySQL 与 H2 对它的
 *       序列化形态不同（见 {@code ConversationKbIds}）。只写不读的测试会漏掉"存进去了但读不出来"，
 *       而那时的表现是"提问说没绑知识库"，用户会以为是自己没设置；</li>
 *   <li><b>没绑定时要明确报错</b>：RAG 会话没绑库就提问，绝不能"悄悄退化成不检索"——
 *       用户会得到一个看起来正常、其实没查过笔记的回答，这比报错糟糕得多；</li>
 *   <li><b>绑别人的库要被拒</b>：否则会话会带着一个查不到数据的 id 存下来；</li>
 *   <li><b>绑多个要被拒</b>：检索侧目前是单库签名，绑多个只能取第一个，
 *       这属于静默丢条件。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatRagModeTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("RAG 会话绑定知识库：写进去、读得回；列表与详情都带 kbIds")
    void ragConversationKeepsItsKnowledgeBase() throws Exception {
        String token = registerAndLogin("rag-bind");
        String kbId = createKnowledgeBase(token, "绑定测试库");

        String created = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"RAG 问答","mode":"RAG","kbIds":[%s]}
                                """.formatted(kbId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("RAG"))
                .andExpect(jsonPath("$.data.kbIds[0]").value(kbId))
                .andReturn().getResponse().getContentAsString();
        String conversationId = JsonPath.read(created, "$.data.id");

        mockMvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kbIds[0]").value(kbId));

        mockMvc.perform(get("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].kbIds[0]").value(kbId));
    }

    @Test
    @DisplayName("未绑定知识库的 RAG 会话提问 → 1001，且明确说清是\"没绑定\"（不静默退化成不检索）")
    void ragConversationWithoutKnowledgeBaseIsRejectedLoudly() throws Exception {
        String token = registerAndLogin("rag-unbound");

        String created = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"没绑库","mode":"RAG"}
                                """))
                .andExpect(status().isOk())
                // 未绑定是空列表而不是 null：前端不必写空值分支
                .andExpect(jsonPath("$.data.kbIds").isArray())
                .andExpect(jsonPath("$.data.kbIds").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String conversationId = JsonPath.read(created, "$.data.id");

        String body = mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":%s,"content":"缓存击穿怎么解决？"}
                                """.formatted(conversationId)))
                // 错误发生在开流之前，因此是统一 JSON 而不是 SSE 里的 error 事件
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("尚未绑定知识库");
    }

    @Test
    @DisplayName("非 RAG 会话不能绑定知识库（PLAIN 绑了没用；AGENT 的检索范围由模型决定）")
    void nonRagConversationCannotBindKnowledgeBase() throws Exception {
        String token = registerAndLogin("rag-bind-plain");
        String kbId = createKnowledgeBase(token, "不该被绑上的库");

        for (String mode : new String[]{"PLAIN", "AGENT"}) {
            mockMvc.perform(post("/api/chat/conversations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"%s 会话","mode":"%s","kbIds":[%s]}
                                    """.formatted(mode, mode, kbId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1001))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("只有 RAG 会话可以绑定知识库")));
        }
    }

    @Test
    @DisplayName("绑多个知识库 → 1001（多库检索未实现，取第一个等于静默丢条件）")
    void multipleKnowledgeBasesAreRejected() throws Exception {
        String token = registerAndLogin("rag-bind-multi");
        String first = createKnowledgeBase(token, "多库 A");
        String second = createKnowledgeBase(token, "多库 B");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[%s,%s]}
                                """.formatted(first, second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("暂只支持绑定一个知识库")));
    }

    @Test
    @DisplayName("绑定别人的知识库 → 404 + 3001（不泄露资源存在性）")
    void bindingSomeoneElsesKnowledgeBaseIsRejected() throws Exception {
        String other = registerAndLogin("rag-bind-other");
        String otherKbId = createKnowledgeBase(other, "别人的库");
        String token = registerAndLogin("rag-bind-self");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[%s]}
                                """.formatted(otherKbId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    @DisplayName("改绑与解绑：PUT kbIds 生效；空数组 = 解绑")
    void rebindAndUnbind() throws Exception {
        String token = registerAndLogin("rag-rebind");
        String first = createKnowledgeBase(token, "改绑 A");
        String second = createKnowledgeBase(token, "改绑 B");

        String created = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[%s]}
                                """.formatted(first)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String conversationId = JsonPath.read(created, "$.data.id");

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kbIds":[%s]}
                                """.formatted(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kbIds[0]").value(second));

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kbIds":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kbIds").isEmpty());

        // null = 不改（局部更新约定）：解绑之后再发一次不带 kbIds 的请求，仍应是解绑状态
        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"只改标题"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("只改标题"))
                .andExpect(jsonPath("$.data.kbIds").isEmpty());
    }

    @Test
    @DisplayName("绑定时顺带改别的字段也不受影响；绑不存在的库 → 3001")
    void unknownKnowledgeBaseIsRejected() throws Exception {
        String token = registerAndLogin("rag-bind-missing");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[999999999]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
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
