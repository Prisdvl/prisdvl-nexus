package com.lky.nexus.app.agent;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.AnswerPolicy;
import com.lky.nexus.app.agent.tool.AgentToolFactory;
import com.lky.nexus.app.agent.tool.ExecutingToolCallback;
import com.lky.nexus.app.agent.tool.ToolCatalog;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tool 资源（Phase 4.3）：目录、启停、Agent 白名单——以及"停用<b>真的</b>会生效"。
 *
 * <p>这几条必须在装配层验，而不是在 Service 的方法返回值上验：
 * 4.3 要消灭的是"看起来能配、实际没人读"的假旋钮（§六十七）。所以每条"改配置"的断言都跟一句
 * "装配结果变了"——配置写进库、列表也显示出来了，但工具照样装给 Agent，才是最难查的那种坏。
 *
 * <p>另外两条同样重要：
 * <ol>
 *   <li><b>回归线</b>：默认用户装配出来的工具里<b>必须有 knowledge_search</b>。
 *       4.3 动了工具装配这条主链路，而对话"会不会检索"正是本项目最怕静默退化的地方
 *       （历史事故见 {@code AgentContext} 的类注释）；</li>
 *   <li><b>0 个工具要可计数</b>：用户把所有工具都停用后，模型退化成"只凭自身知识回答"，
 *       这在界面上与"检索了但没命中"看起来一样，只能靠计数分辨（§1.11 第 10 条）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Tool 资源：目录 / 启停 / Agent 白名单 / 装配生效")
class ToolResourceTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentToolFactory toolFactory;

    @Autowired
    private ToolCatalog toolCatalog;

    @Autowired
    private ObservabilityRegistry observability;

    @Test
    @DisplayName("目录：内置工具按登记顺序返回，默认全开，且不下发 jsonSchema（那是 MCP/模型的格式）")
    void listsBuiltinToolsEnabledByDefault() throws Exception {
        String token = registerAndLogin("tool-list");

        mockMvc.perform(get("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                // 顺序 = 登记顺序（检索在前）：界面与 MCP 的 tools/list 用的是同一个顺序
                .andExpect(jsonPath("$.data[0].code").value("knowledge_search"))
                .andExpect(jsonPath("$.data[0].name").value("知识库检索"))
                .andExpect(jsonPath("$.data[0].category").value("RETRIEVAL"))
                .andExpect(jsonPath("$.data[1].code").value("calculator"))
                .andExpect(jsonPath("$.data[1].category").value("COMPUTE"))
                // 新用户没有任何覆盖行 → 全部默认启用。"没有行 = 用代码侧默认"这条语义
                // 一旦被实现成"没有行 = 停用"，所有既有用户的检索会一起消失
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].builtin").value(true))
                .andExpect(jsonPath("$.data[0].source").value("BUILTIN"))
                .andExpect(jsonPath("$.data[0].description").exists())
                .andExpect(jsonPath("$.data[0].jsonSchema").doesNotExist());
    }

    @Test
    @DisplayName("启停：停用后该用户所有 Agent 立刻不再装配它；重新打开即恢复")
    void disablingAToolTakesEffectOnAssembly() throws Exception {
        String token = registerAndLogin("tool-disable");
        Long userId = currentUserId(token);

        // 回归线：4.3 之前是"写死一个检索工具"，现在默认仍然必须装它
        assertThat(assembledCodes(userId)).containsExactly("knowledge_search", "calculator");

        mockMvc.perform(put("/api/tools/knowledge_search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("knowledge_search"))
                .andExpect(jsonPath("$.data.enabled").value(false));

        // 关键断言：模型连这个工具的存在都看不到（不是"调用了被拒"，那样模型会一直试）
        assertThat(assembledCodes(userId)).containsExactly("calculator");
        mockMvc.perform(get("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data[0].enabled").value(false))
                // 停用不等于不可见：它还得留在目录里，否则用户没法把它打开
                .andExpect(jsonPath("$.data[0].code").value("knowledge_search"));

        mockMvc.perform(put("/api/tools/knowledge_search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        assertThat(assembledCodes(userId)).containsExactly("knowledge_search", "calculator");
    }

    @Test
    @DisplayName("启停：覆盖行是按用户写的，一个用户停用不影响另一个用户")
    void overridesArePerUser() throws Exception {
        String owner = registerAndLogin("tool-scope-owner");
        String other = registerAndLogin("tool-scope-other");

        mockMvc.perform(put("/api/tools/calculator")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk());

        assertThat(assembledCodes(currentUserId(owner))).containsExactly("knowledge_search");
        assertThat(assembledCodes(currentUserId(other))).containsExactly("knowledge_search", "calculator");
    }

    @Test
    @DisplayName("全部停用：装配结果是 0 个工具，模型退化成「只凭自身知识回答」——这件事必须可计数")
    void zeroToolsIsCounted() throws Exception {
        String token = registerAndLogin("tool-all-off");
        Long userId = currentUserId(token);
        long before = observability.snapshot().getOrDefault(ObservabilityRegistry.AGENT_TOOLS_EMPTY, 0L);

        for (String code : List.of("knowledge_search", "calculator")) {
            mockMvc.perform(put("/api/tools/" + code)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"enabled":false}
                                    """))
                    .andExpect(status().isOk());
        }

        assertThat(assembledCodes(userId)).isEmpty();
        // 计数是"这次回答为什么没有引用资料"的唯一线索：界面上它与"检索了没命中"长得一样
        assertThat(observability.snapshot().getOrDefault(ObservabilityRegistry.AGENT_TOOLS_EMPTY, 0L))
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("提示词点名了却被停用的工具：不拦截，但必须留痕（4.3 才可能出现的不一致）")
    void promptNamingADisabledToolIsCounted() throws Exception {
        String token = registerAndLogin("tool-prompt-mismatch");
        Long userId = currentUserId(token);
        long before = observability.snapshot().getOrDefault(ObservabilityRegistry.TOOL_PROMPT_MISMATCH, 0L);

        mockMvc.perform(put("/api/tools/knowledge_search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk());

        // 内置默认 Agent 的提示词写死了"必须先调用 knowledge_search 检索"，
        // 而这次装配里没有它——模型会去用一把看不见的工具。
        // 我们不改用户的提示词、也不偷偷把工具装回去（那会让停用变成假旋钮），只计数
        assertThat(assembledCodes(userId)).containsExactly("calculator");

        assertThat(observability.snapshot().getOrDefault(ObservabilityRegistry.TOOL_PROMPT_MISMATCH, 0L))
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("启停：不认识的 code 是 5011/404（既不静默忽略，也不假装写成功）")
    void unknownCodeIsRejected() throws Exception {
        String token = registerAndLogin("tool-unknown");

        mockMvc.perform(put("/api/tools/no_such_tool")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5011))
                // 报错要把可用的值说清楚，否则用户只能靠猜
                .andExpect(jsonPath("$.message").value(containsString("knowledge_search")));

        // enabled 缺失是参数问题（1001），不能默默当成 false 把工具关掉
        mockMvc.perform(put("/api/tools/calculator")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("Agent 白名单：可绑定、随详情返回、复制带走、用空列表解绑（=不限制）")
    void agentWhitelistRoundTrip() throws Exception {
        String token = registerAndLogin("tool-whitelist");

        String created = mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"only-calc","name":"只用计算器","toolCodes":["calculator","calculator"]}
                                """))
                .andExpect(status().isOk())
                // 去重：绑两次同一个工具不是"绑了 2 个"
                .andExpect(jsonPath("$.data.toolCodes.length()").value(1))
                .andExpect(jsonPath("$.data.toolCodes[0]").value("calculator"))
                .andReturn().getResponse().getContentAsString();
        String agentId = JsonPath.read(created, "$.data.id");

        // 白名单真的会被装配层读到：这个 Agent 只有计算器，没有检索。
        // 先发布——草稿态本来就不允许用于对话（§十八"先测试再发布"），那条守卫是对的，
        // 所以这里必须走"发布后装配"这条真实路径，而不是绕过它
        Long userId = currentUserId(token);
        publish(token, agentId);
        assertThat(assembledCodesOf(userId, agentService.requireDefinition(userId, Long.valueOf(agentId))))
                .containsExactly("calculator");

        // 复制品带走白名单（否则"复制一个专用 Agent"得到的是个全工具的 Agent）
        String copy = mockMvc.perform(post("/api/agents/" + agentId + "/duplicate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(copy, "$.data.toolCodes")).containsExactly("calculator");

        // 空列表 = 解绑（回到"不限制"），与 kbIds 的语义保持一致；
        // 若实现里漏了这条，updateById 会跳过 null 字段，解绑静默失效
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolCodes":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCodes").isEmpty());
        assertThat(assembledCodesOf(userId, agentService.requireDefinition(userId, Long.valueOf(agentId))))
                .containsExactly("knowledge_search", "calculator");
    }

    @Test
    @DisplayName("Agent 白名单：绑定不存在的工具在写入口就被拒（不留下「绑了但用不了」的状态）")
    void agentWhitelistRejectsUnknownCode() throws Exception {
        String token = registerAndLogin("tool-whitelist-bad");

        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"bad-tools","name":"绑了不存在的工具","toolCodes":["no_such_tool"]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5011));
    }

    @Test
    @DisplayName("用户停用优先于 Agent 白名单：白名单里写了它也救不回来")
    void userDisableWinsOverWhitelist() throws Exception {
        String token = registerAndLogin("tool-priority");
        Long userId = currentUserId(token);

        mockMvc.perform(put("/api/tools/calculator")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk());

        AgentDefinition definition = new AgentDefinition(1L, "calc-only", "只要计算器", "回答",
                "chat.agent", List.of(), List.of("calculator"), AnswerPolicy.KB_PREFERRED, false);
        // 两个减项同时命中 → 0 个工具。这是刻意的：一个被用户停用的工具，
        // 不该因为某个 Agent 的白名单里写了它而复活
        assertThat(assembledCodesOf(userId, definition)).isEmpty();
    }

    @Test
    @DisplayName("MCP：tools/list 由注册表生成（不再是手写的那一份 schema），tools/call 走同一个执行壳")
    void mcpToolsComeFromTheRegistry() throws Exception {
        // 2026-10-01（Phase 5.1）起 /mcp 与其余端点同一套身份：必须带 JWT。
        // 此前这里靠 `X-Nexus-User: 1` 自证身份，而那条路径是公开的——见 decisions.md B.42。
        // 本用例只关心"能力清单与执行壳"，身份契约由 McpEndpointAuthTest 单独钉住。
        String token = registerAndLogin("tool-mcp");

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(3))
                .andExpect(jsonPath("$.result.tools[0].name").value("knowledge_search"))
                // 描述来自 @Tool 注解（单一来源），不是这里再写一遍
                .andExpect(jsonPath("$.result.tools[0].description").value(containsString("混合检索")))
                // schema 来自方法签名的反射：这条断言就是"第二份硬编码已经消失"的证据
                .andExpect(jsonPath("$.result.tools[0].inputSchema.properties.query").exists())
                // 但反射出来的 schema 必须与<b>真实契约</b>一致：topK 的语义是"可选，默认 5"
                // （@ToolParam 的 required 默认是 true，不显式写 false 就会被标成必填——
                //  4.3 真机实测踩到过，见 decisions.md B.39）。这条断言盯的就是那个默认值。
                .andExpect(jsonPath("$.result.tools[0].inputSchema.required.length()").value(1))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.required[0]").value("query"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.properties.topK").exists())
                .andExpect(jsonPath("$.result.tools[1].name").value("calculator"))
                // qa_ask 不是 @Tool 方法（它是"跑一次默认 Agent"的便利入口），仍写在端点里
                .andExpect(jsonPath("$.result.tools[2].name").value("qa_ask"));

        // 漏传 topK 必须走默认值，而不是炸在拆箱上。
        // 这条是 4.3 真机上抓到的真实回归：把参数原样交给工具（正确做法）之后，
        // 缺省的 topK 绑到一个 int 参数上得到 null，拆箱抛 NullPointerException——
        // 4.3 之前被 MCP 那句 arguments.path("topK").asInt(5) 盖住了。见 decisions.md B.39。
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                                 "params":{"name":"knowledge_search","arguments":{"query":"缓存击穿"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value(not(containsString("NullPointerException"))))
                .andExpect(jsonPath("$.result.content[0].text").value(not(containsString("执行失败"))));

        // 新工具既能被发现也能被调用：只列出来却调不动，比不列更糟。
        // text 断言写成"完全相等"而不是 contains：框架会把工具返回值序列化成 JSON 字符串
        // （String 也带引号），MCP 客户端看到 `"1+2*4 = 9"` 会以为是工具的 bug——
        // 这条断言就是"已经拆回原文"的证据
        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                                 "params":{"name":"calculator","arguments":{"expression":"1+2*4"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value("1+2*4 = 9"));

        mockMvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                                 "params":{"name":"no_such_tool","arguments":{}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    @DisplayName("留痕不变量：注册表里每个工具，成功调用一次都恰好留下一条痕迹（谁都不许少报）")
    void everyToolLeavesExactlyOneTrace() {
        // 4.3 真机上的第二个回归（decisions.md B.40）：痕迹原先靠工具自己调 AgentContext.record 上报，
        // 于是 calculator 执行成功了，日志却写"工具调用 0 次"、界面上一张卡片也没有。
        // 这条断言不关心"谁来报"（工具自报还是执行壳兜底），只钉住**结果**：
        // 一次成功的调用 → 恰好一条痕迹。谁新增工具时忘了管这件事，这里就会红。
        for (ToolCatalog.Registered tool : toolCatalog.all()) {
            List<AgentContext.ToolCallRecord> trace = new ArrayList<>();
            ToolContext context = new ToolContext(AgentContext.of(1L, AgentContext.collector(trace)));

            ExecutingToolCallback.Outcome outcome = new ExecutingToolCallback(tool, observability)
                    .invoke(argumentFor(tool.code()), context);

            assertThat(outcome.failed()).as("%s 不该失败", tool.code()).isFalse();
            assertThat(trace).as("%s 成功执行后必须恰好留下一条痕迹", tool.code()).hasSize(1);
            assertThat(trace.get(0).tool()).as("痕迹要记清是哪个工具").isEqualTo(tool.code());
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 每个工具一组最小可用入参（要能真的跑通，否则断言的是"失败也留痕"）。 */
    private String argumentFor(String code) {
        return switch (code) {
            case "knowledge_search" -> """
                    {"query":"缓存击穿"}""";
            case "calculator" -> """
                    {"expression":"1+2"}""";
            default -> throw new IllegalStateException("注册表里出现了没有测试入参的工具：" + code
                    + "——新增工具时请在这里补一组入参，别让它悄悄漏出留痕不变量");
        };
    }

    /** 该用户的默认 Agent 实际会装到哪些工具（按装配顺序）。 */
    private List<String> assembledCodes(Long userId) {
        return codesOf(toolFactory.assemble(userId, agentService.resolveDefault(userId)));
    }

    private List<String> assembledCodesOf(Long userId, AgentDefinition definition) {
        return codesOf(toolFactory.assemble(userId, definition));
    }

    private List<String> codesOf(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().name()).toList();
    }

    private void publish(String token, String agentId) throws Exception {
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PUBLISHED"}
                                """))
                .andExpect(status().isOk());
    }

    private Long currentUserId(String token) throws Exception {
        return Long.valueOf(JsonPath.read(mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id"));
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
