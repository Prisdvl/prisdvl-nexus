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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话选用 Agent 的准入规则（Phase 4.1）。
 *
 * <p>会话与 Agent 的关系是"引用"，而引用最容易出错的地方不是存不下来，而是<b>存下来了但没用</b>：
 * 用户在界面上选了某个 Agent，接口 200，提问却仍走默认助手——全程没有任何报错。
 * 因此这里把三条边界都钉在 HTTP 层：
 * <ol>
 *   <li>只有 AGENT 模式能选（PLAIN/RAG 选了必须报错，不能静默忽略）；</li>
 *   <li>草稿（DRAFT）不能选：5008，这就是"先测试再发布"里"发布"的那道闸；</li>
 *   <li>选了别人的 Agent 返回 404（不泄露存在性）。</li>
 * </ol>
 *
 * <p>最后一条是本类里最有价值的一条：<b>提问时 Agent 已被停用</b>。会话建好之后 Agent 可以被
 * 停用或退回草稿，"创建时校验过"不能替代"用的时候再校验"——否则停用形同虚设。
 * 断言同时要求"没有留下任何消息"：失败必须发生在产生副作用之前。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("会话选用 Agent：准入与失效")
class ConversationAgentBindingTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AGENT 会话选用已发布的 Agent：创建与详情都带 agentId")
    void agentConversationKeepsSelectedAgent() throws Exception {
        String token = registerAndLogin("conv-agent");
        String agentId = publishedAgent(token, "conv-agent-pick");

        String created = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"用我的 Agent","mode":"AGENT","agentId":%s}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("AGENT"))
                .andExpect(jsonPath("$.data.agentId").value(agentId))
                .andReturn().getResponse().getContentAsString();
        String conversationId = JsonPath.read(created, "$.data.id");

        // 读回也要一致：只写进库、读不出来的话，前端刷新后就"丢"了选择
        mockMvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentId").value(agentId));
    }

    @Test
    @DisplayName("AGENT 会话不指定 Agent：agentId 为空（运行期用内置默认，不是错误）")
    void agentConversationWithoutSelectionFallsBackToBuiltin() throws Exception {
        String token = registerAndLogin("conv-agent-default");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"默认助手","mode":"AGENT"}
                                """))
                .andExpect(status().isOk())
                // "没选"是合法状态：AGENT 模式永远有内置默认兜底（与 RAG 的 kbIds 相反）
                .andExpect(jsonPath("$.data.agentId").doesNotExist());
    }

    @Test
    @DisplayName("非 AGENT 模式选用 Agent 一律 1001：静默忽略比报错更坏")
    void onlyAgentModeMaySelectAgent() throws Exception {
        String token = registerAndLogin("conv-agent-mode");
        String agentId = publishedAgent(token, "conv-agent-mode-pick");

        for (String mode : new String[]{"PLAIN", "RAG"}) {
            mockMvc.perform(post("/api/chat/conversations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"%s","mode":"%s","agentId":%s}
                                    """.formatted(mode, mode, agentId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1001));
        }
    }

    @Test
    @DisplayName("草稿 Agent 不能被选用（5008）——这就是「先测试再发布」的闸门")
    void draftAgentCannotBeUsedInConversation() throws Exception {
        String token = registerAndLogin("conv-agent-draft");
        String draftId = createAgent(token, "conv-draft", "还没发布");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"草稿","mode":"AGENT","agentId":%s}
                                """.formatted(draftId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5008));
    }

    @Test
    @DisplayName("选用别人的 Agent → 404 + 5006（不泄露存在性）")
    void foreignAgentLooksMissing() throws Exception {
        String owner = registerAndLogin("conv-agent-owner");
        String intruder = registerAndLogin("conv-agent-intruder");
        String agentId = publishedAgent(owner, "conv-foreign");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"越权","mode":"AGENT","agentId":%s}
                                """.formatted(agentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5006));
    }

    @Test
    @DisplayName("换 Agent：AGENT 会话可以改，PLAIN 会话改被拒")
    void updateSwitchesAgentOnlyForAgentMode() throws Exception {
        String token = registerAndLogin("conv-agent-update");
        String first = publishedAgent(token, "conv-switch-a");
        String second = publishedAgent(token, "conv-switch-b");
        String conversationId = createConversation(token, "AGENT", first);
        String plainId = createConversation(token, "PLAIN", null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":%s}
                                """.formatted(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentId").value(second));

        mockMvc.perform(put("/api/chat/conversations/" + plainId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":%s}
                                """.formatted(second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("清空 Agent：AGENT 会话回到内置默认（resetAgent）；与 agentId 同时传 1001；非 AGENT 被拒")
    void resetAgentReturnsToBuiltin() throws Exception {
        String token = registerAndLogin("conv-agent-reset");
        String agentId = publishedAgent(token, "conv-reset-me");
        String conversationId = createConversation(token, "AGENT", agentId);
        String plainId = createConversation(token, "PLAIN", null);

        // 没有这条路径时，选过 Agent 的会话回不到默认，"不用自定义 Agent 了"只能新建会话
        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resetAgent":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentId").doesNotExist());

        // 自相矛盾的请求要报错，而不是"按其中一个生效"——那是最难查的一类行为
        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resetAgent":true,"agentId":%s}
                                """.formatted(agentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(put("/api/chat/conversations/" + plainId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resetAgent":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("提问时 Agent 已被停用 → 409 + 5009，且不留下半条消息")
    void disabledAgentFailsBeforeAnySideEffect() throws Exception {
        String token = registerAndLogin("conv-agent-disabled");
        String agentId = publishedAgent(token, "conv-later-disabled");
        String conversationId = createConversation(token, "AGENT", agentId);

        // 会话建好之后才停用：创建时的校验不能替代使用时的校验，否则"停用"形同虚设
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":%s,"content":"停用之后还能问吗"}
                                """.formatted(conversationId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5009));

        // 关键的第二半：失败发生在落库之前。否则会留下一条用户消息 + 一条永远 STREAMING 的
        // 空助手消息，用户看到的是"消息凭空多了一条"，而报错信息看起来与它无关
        mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 建一个 Agent 并置为 PUBLISHED（只有 PUBLISHED 能被会话选用）。 */
    private String publishedAgent(String token, String code) throws Exception {
        String agentId = createAgent(token, code, "会话选用 " + code);
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PUBLISHED"}
                                """))
                .andExpect(status().isOk());
        return agentId;
    }

    private String createAgent(String token, String code, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s"}
                                """.formatted(code, name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createConversation(String token, String mode, String agentId) throws Exception {
        String agentPart = agentId == null ? "" : ",\"agentId\":" + agentId;
        return JsonPath.read(mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s 会话","mode":"%s"%s}
                                """.formatted(mode, mode, agentPart)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
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
