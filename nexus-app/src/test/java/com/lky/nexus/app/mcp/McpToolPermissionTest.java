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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 直调路径的<b>工具权限</b>（Phase 5.9 八）。
 *
 * <p><b>为什么这个类非存在不可</b>：工具的"启停"开关此前只对 Agent 装配生效
 * （{@code AgentToolFactory} 按用户启停过滤），而 MCP 的 {@code tools/list} 从
 * {@code ToolCatalog.all()} 列全部、{@code tools/call} 直接执行——<b>一次判定都没有</b>。
 * 用户在界面上关掉检索工具之后，界面上那条 Agent 确实不再检索了，
 * 但同一个工具仍然可以从 {@code /mcp} 调起来，还照样读他的知识库。
 * 那正是本项目一路在防的那类东西：<b>开关看起来生效，实际只生效了一半</b>，
 * 而且没有任何报错会提示这件事。
 *
 * <p><b>两半必须一起验</b>：只在 {@code tools/list} 里藏起来、{@code call} 仍然能调，
 * 是比不藏更坏的一种谎（客户端会认为"这个工具不存在"，而它其实在跑）。
 * 所以下面的用例成对断言：列不出来 <b>且</b> 调不动。
 *
 * <p>test profile 下 ES 与 pgvector 都是 Disabled 实现，检索本身会走降级分支——
 * 这没关系：本类断言的是"这次调用有没有被允许"，而不是"检索到了什么"，
 * 因此不依赖任何外部基础设施。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MCP：tools/list 与 tools/call 遵守同一份用户工具权限")
class McpToolPermissionTest {

    private static final String PASSWORD = "McpPerm!Pwd1";
    private static final String TOOL = "knowledge_search";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("★ 停用之后：list 不再列出它，call 也必须拒绝（业务码 5004）")
    void disabledToolDisappearsFromListAndIsRefusedOnCall() throws Exception {
        String token = registerAndLogin("mcp-perm-disabled");

        // 停用前先在列表里看到它：否则下面的"看不到"可能只是因为一开始就没有，
        // 那这条用例就成了空断言（这个项目已经吃过一次空断言的亏）
        assertThat(toolNames(token)).contains(TOOL);

        setToolEnabled(token, false);
        assertThat(toolNames(token))
                .as("被用户停用的工具不该出现在能力清单里")
                .doesNotContain(TOOL)
                // qa_ask 不是注册表里的 @Tool，而是"跑一次默认 Agent"的入口，因此不受这个开关影响；
                // 它内部的检索同样按用户启停过滤（见 McpEndpoint 类注释）
                .contains("qa_ask");

        String body = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcKnowledgeSearch()))
                .andExpect(status().isOk())
                // 拒绝 = JSON-RPC error，而不是 result.isError=true：
                // 后者在本类里的含义是"工具跑了但失败"，混用会让调用方分不清
                // 是用户的开关生效了，还是检索真出错了
                .andExpect(jsonPath("$.error.code").value(-32003))
                .andExpect(jsonPath("$.error.data.businessCode").value(5004))
                .andExpect(jsonPath("$.error.data.tool").value(TOOL))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("工具调用无权限")))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(TOOL);
    }

    @Test
    @DisplayName("重新启用之后：list 里回来了，call 也不再被拒（开关是双向的，不是单向锁死）")
    void reEnabledToolIsListedAndCallableAgain() throws Exception {
        String token = registerAndLogin("mcp-perm-reenabled");

        setToolEnabled(token, false);
        assertThat(toolNames(token)).doesNotContain(TOOL);

        setToolEnabled(token, true);

        assertThat(toolNames(token)).contains(TOOL);

        // 调用真的走通了：这条用户没有知识库，工具会如实说"你还没有任何知识库"，
        // 因此响应里应当有 result.content[0].text，而不是 error
        String body = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcKnowledgeSearch()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("你还没有任何知识库");
    }

    @Test
    @DisplayName("权限是按用户算的：A 停用不影响 B（不是把工具全局关掉）")
    void permissionIsScopedToTheUser() throws Exception {
        String tokenA = registerAndLogin("mcp-perm-alice");
        String tokenB = registerAndLogin("mcp-perm-bob");

        setToolEnabled(tokenA, false);

        assertThat(toolNames(tokenA)).doesNotContain(TOOL);
        assertThat(toolNames(tokenB)).as("B 没动过开关，工具应当照旧可用").contains(TOOL);

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rpcKnowledgeSearch()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("未知工具仍然是 -32601「Unknown tool」，不能被说成「无权限」")
    void unknownToolIsNotReportedAsPermissionDenied() throws Exception {
        String token = registerAndLogin("mcp-perm-unknown");

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                                 "params":{"name":"no_such_tool","arguments":{}}}"""))
                .andExpect(status().isOk())
                // 两种失败必须分得开：一个是"名字写错了"（去查文档），
                // 一个是"你的开关生效了"（去界面打开）。合成一句话会把人引到错的地方
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.error.data").doesNotExist());
    }

    // ------------------------------------------------------------------

    /** 该用户 {@code tools/list} 里的工具名（含 qa_ask）。 */
    private List<String> toolNames(String token) throws Exception {
        String body = mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.result.tools[*].name");
    }

    private void setToolEnabled(String token, boolean enabled) throws Exception {
        mockMvc.perform(put("/api/tools/" + TOOL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":%s}
                                """.formatted(enabled)))
                .andExpect(status().isOk());
    }

    private String rpcKnowledgeSearch() {
        return """
               {"jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{"name":"knowledge_search","arguments":{"query":"缓存击穿","topK":3}}}""";
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }
}
