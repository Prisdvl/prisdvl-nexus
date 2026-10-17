package com.lky.nexus.app.mcp;

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
 * {@code POST /mcp} 的<b>身份</b>契约（Phase 5.1，修 P0-1）。
 *
 * <p><b>为什么这个类非存在不可</b>：在 2026-10-01 之前，{@code /mcp} 在
 * {@code SecurityConfig.PUBLIC_ENDPOINTS} 里（免认证），调用方用 {@code X-Nexus-User: <任意 userId>}
 * 声明自己是谁，服务端照单全收。那条路径把整个应用 43 处 {@code CurrentUser.requireId()} 与
 * "越权返回 404"建立起来的隔离<b>整条绕开</b>：知道（或枚举出）一个雪花 id 就能
 * 读走那个人的全部笔记正文，并用他的默认 Agent 花他的额度。
 *
 * <p>当时这个问题只是"读代码得出的结论"（见 docs/archive/reports/productization-gap-analysis.md §6.2 的自认边界）。
 * 这个类把它钉成可复现的事实：<b>没有凭据就进不来；有凭据也只能看到凭据主人自己的东西</b>。
 *
 * <p><b>断言为什么这么选</b>：test profile 下 ES 与 pgvector 都是 Disabled 实现，
 * "检索到没有内容"是环境噪声，不能用来判断身份。所以这里断言的是
 * <b>只有 A 才可能产生的那句话</b>——A 名下有一个<b>被停用</b>的知识库，
 * 工具会点名"已跳过：<库名>（已禁用）"。名字是 A 的，B 的库名一个字都不该出现。
 * 若身份真被请求头改写成 B，A 的库名不可能出现在输出里，这条断言必然失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MCP：身份只来自 JWT，请求头不能自证身份")
class McpEndpointAuthTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("无凭据 POST /mcp → 401 / 2001（不再是公开端点）")
    void unauthenticatedMcpIsRejected() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcToolsList()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001))
                // 统一契约：失败体里没有 result，客户端不会把错误当成一次成功的 tools/list
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("只带 X-Nexus-User 请求头 → 仍然 401（自证身份这条路已删除）")
    void userHeaderAloneGrantsNothing() throws Exception {
        String victim = registerAndLogin("mcp-victim-header");

        mockMvc.perform(post("/mcp")
                        .header("X-Nexus-User", String.valueOf(currentUserId(victim)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcKnowledgeSearch()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("带 A 的 token 伪造 B 的 userId → 拿到的是 A 的事实，B 的库名一个字都不出现")
    void tokenWinsAndForgedHeaderIsIgnored() throws Exception {
        String victimToken = registerAndLogin("mcp-victim");
        Long victimId = currentUserId(victimToken);
        createKnowledgeBase(victimToken, "victim 的活跃库");

        String attackerToken = registerAndLogin("mcp-attacker");
        Long attackerKb = Long.valueOf(createKnowledgeBase(attackerToken, "attacker 的停用库"));
        disableKnowledgeBase(attackerToken, attackerKb);

        String body = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + attackerToken)
                        // 这就是 P0-1 的原始利用方式：用请求头声明自己是受害者
                        .header("X-Nexus-User", String.valueOf(victimId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcKnowledgeSearch()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String text = JsonPath.read(body, "$.result.content[0].text");
        // 调用方的库被点名跳过：这是**调用方自己**的数据，只有身份取自 A 的 token 才可能出现
        assertThat(text).contains("attacker 的停用库", "已禁用");
        assertThat(text).doesNotContain("victim 的活跃库");
    }

    @Test
    @DisplayName("零知识库的调用方：如实说「你还没有任何知识库」——不因为伪造的头就变成别人")
    void zeroKnowledgeBaseCallerSeesItsOwnEmptyState() throws Exception {
        String victimToken = registerAndLogin("mcp-victim-kb");
        createKnowledgeBase(victimToken, "victim 的笔记库");

        String attackerToken = registerAndLogin("mcp-empty");

        String body = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + attackerToken)
                        .header("X-Nexus-User", String.valueOf(currentUserId(victimToken)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcKnowledgeSearch()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String text = JsonPath.read(body, "$.result.content[0].text");
        assertThat(text).contains("你还没有任何知识库");
        assertThat(text).doesNotContain("victim 的笔记库");
    }

    @Test
    @DisplayName("正向对照：带 token 的 tools/list 仍然正常，能力清单没被这次收紧改坏")
    void toolsListStillWorksWithToken() throws Exception {
        String token = registerAndLogin("mcp-tools-list");

        String body = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcToolsList()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("knowledge_search");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 一条 {@code tools/list} 请求。 */
    private String rpcToolsList() {
        return """
               {"jsonrpc":"2.0","id":1,"method":"tools/list"}""";
    }

    /** 一条 {@code tools/call}：调 knowledge_search，参数固定（断言的是身份，不是检索质量）。 */
    private String rpcKnowledgeSearch() {
        return """
               {"jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{"name":"knowledge_search","arguments":{"query":"缓存击穿","topK":3}}}""";
    }

    private void disableKnowledgeBase(String token, Long kbId) throws Exception {
        mockMvc.perform(put("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk());
    }

    private Long currentUserId(String token) throws Exception {
        return Long.valueOf(JsonPath.read(mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));

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
