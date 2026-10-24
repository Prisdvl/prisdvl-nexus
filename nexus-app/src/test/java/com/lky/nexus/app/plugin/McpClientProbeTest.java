package com.lky.nexus.app.plugin;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.agent.mapper.ToolRegistryMapper;
import com.lky.nexus.app.agent.model.entity.ToolRegistryPO;
import com.lky.nexus.app.plugin.mapper.McpServerMapper;
import com.lky.nexus.app.plugin.mcp.McpToolInjector;
import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 外部 MCP server 探测闭环。
 *
 * <p>用 {@link HttpServer} 起一个只认 JSON-RPC 的假 server，把"连一次 tools/list → 落库 →
 * 登记工具"这条链路整条跑通；不碰任何真实 MCP 进程，也不需要网络。
 *
 * <p>另外三条用例专门钉住"失败也要有话说"：上游 500、端口根本没开、stdio 被服务端关闭——
 * 三种都会 200 返回 {@code ok=false}，但 {@code error} 里的原因必须各不相同。
 * 静默当成"没有工具"是这次改动最不该犯的错。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpClientProbeTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private McpServerMapper serverMapper;

    @Autowired
    private ToolRegistryMapper toolRegistryMapper;

    @Autowired
    private McpToolInjector injector;

    @Autowired
    private JsonMapper json;

    private HttpServer fakeServer;
    private volatile int status = 200;

    @BeforeEach
    void startFakeServer() throws IOException {
        fakeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeServer.createContext("/mcp", this::handle);
        fakeServer.start();
    }

    @AfterEach
    void stopFakeServer() {
        if (fakeServer != null) {
            fakeServer.stop(0);
        }
    }

    @Test
    @DisplayName("探测成功：tools/list 落库、注册 mcp__ 工具、注入器能把它装给 Agent")
    void probeRegistersTools() throws Exception {
        String token = registerAndLogin("mcp-ok");
        long serverId = createServer(token, "Fake Echo", "http", baseUrl(), null, true);

        MvcResult probe = mockMvc.perform(post("/api/mcp-servers/{id}/probe", serverId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.error").doesNotExist())
                .andExpect(jsonPath("$.data.tools[0].name").value("echo"))
                .andExpect(jsonPath("$.data.tools[0].description").value("回声"))
                .andExpect(jsonPath("$.data.lastOkAt").isNotEmpty())
                .andExpect(jsonPath("$.data.tookMs").isNumber())
                .andReturn();

        String body = probe.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // tookMs 之类允许是数字；这里只看工具数量，避免和全局 Long→String 序列化规则纠缠
        assertThat((List<?>) JsonPath.read(body, "$.data.tools")).hasSize(1);

        McpServerPO stored = serverMapper.selectById(serverId);
        assertThat(stored.getToolsJson()).contains("echo");
        assertThat(stored.getLastOkAt()).isNotNull();
        assertThat(stored.getLastError()).isNull();
        assertThat(stored.getSlug()).isEqualTo("fake-echo");

        ToolRegistryPO tool = toolRegistryMapper.selectOne(Wrappers.<ToolRegistryPO>lambdaQuery()
                .eq(ToolRegistryPO::getCode, "mcp__fake-echo__echo"));
        assertThat(tool).isNotNull();
        assertThat(tool.getEnabled()).isTrue();
        assertThat(tool.getSource()).isEqualTo("MCP");

        // 装配侧：启用中的 server 的工具必须能被注入；停用后立即消失
        List<com.lky.nexus.app.agent.tool.ToolCatalog.Registered> injected =
                injector.toolsFor(stored.getUserId(), null);
        assertThat(injected).extracting(com.lky.nexus.app.agent.tool.ToolCatalog.Registered::code)
                .containsExactly("mcp__fake-echo__echo");
        assertThat(injected.get(0).callback().getToolDefinition().description()).isEqualTo("回声");

        mockMvc.perform(putJson(serverId, token, "{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(injector.toolsFor(stored.getUserId(), null)).isEmpty();
    }

    @Test
    @DisplayName("探测失败：上游 500 时给出原因、落 last_error、200 返回而不是静默无工具")
    void probeSurfacesUpstreamFailure() throws Exception {
        status = 500;
        String token = registerAndLogin("mcp-fail");
        long serverId = createServer(token, "Broken", "http", baseUrl(), null, true);

        MvcResult result = mockMvc.perform(post("/api/mcp-servers/{id}/probe", serverId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.error").isNotEmpty())
                .andExpect(jsonPath("$.data.tools").isEmpty())
                .andReturn();

        String error = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.error");
        assertThat(error).contains("500");

        McpServerPO stored = serverMapper.selectById(serverId);
        assertThat(stored.getLastError()).contains("500");
        // 这个 server 从没成功过，last_ok_at 必须是 null —— 探测失败不该伪造一个"上次成功"
        assertThat(stored.getLastOkAt()).isNull();
        // 失败不该登记任何工具：否则模型会看到一个永远调不通的工具
        assertThat(toolRegistryMapper.selectCount(Wrappers.<ToolRegistryPO>lambdaQuery()
                .likeRight(ToolRegistryPO::getCode, "mcp__broken__"))).isZero();
    }

    @Test
    @DisplayName("探测失败：端口没开时给出连接层原因，而不是「没有工具」")
    void probeSurfacesConnectionFailure() throws Exception {
        String token = registerAndLogin("mcp-dead");
        fakeServer.stop(0);
        long serverId = createServer(token, "Dead", "http", baseUrl(), null, true);

        MvcResult result = mockMvc.perform(post("/api/mcp-servers/{id}/probe", serverId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andReturn();

        String error = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.error");
        assertThat(error).isNotBlank();
        assertThat(serverMapper.selectById(serverId).getLastError()).isNotBlank();
    }

    @Test
    @DisplayName("stdio 被服务端关闭时，探测明确说出原因（而不是连不上）")
    void probeExplainsDisabledStdio() throws Exception {
        String token = registerAndLogin("mcp-stdio");
        long serverId = createServer(token, "Local", "stdio", null, "npx -y some-mcp-server", true);

        MvcResult result = mockMvc.perform(post("/api/mcp-servers/{id}/probe", serverId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andReturn();

        String error = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.error");
        assertThat(error).contains("stdio");
    }

    @Test
    @DisplayName("越权与不存在的 server 一律 404（不暴露存在性）")
    void probeOthersServerIsNotFound() throws Exception {
        String owner = registerAndLogin("mcp-owner");
        String other = registerAndLogin("mcp-other");
        long serverId = createServer(owner, "Mine", "http", baseUrl(), null, true);

        mockMvc.perform(post("/api/mcp-servers/{id}/probe", serverId)
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(9201));
    }

    // ---------- helpers ----------

    private String baseUrl() {
        return "http://127.0.0.1:" + fakeServer.getAddress().getPort() + "/mcp";
    }

    private long createServer(String token, String name, String transport, String url, String command,
                              boolean enabled) throws Exception {
        String body = """
                {"name":"%s","transport":"%s"%s%s,"enabled":%s}
                """.formatted(name, transport,
                url == null ? "" : ",\"url\":\"" + url + "\"",
                command == null ? "" : ",\"command\":\"" + command + "\"",
                enabled);
        MvcResult result = mockMvc.perform(post("/api/mcp-servers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Object id = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        return Long.parseLong(String.valueOf(id));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(
            long serverId, String token, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/mcp-servers/{id}", serverId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String registerAndLogin(String prefix) throws Exception {
        String username = prefix + "-" + SEQ.incrementAndGet() + "-" + (System.nanoTime() % 100_000);
        String email = username + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s"}
                                """.formatted(username, PASSWORD, email)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.accessToken");
    }

    /** 一个只认 JSON-RPC 的假 MCP server（initialize / tools/list / tools/call）。 */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (status != 200) {
                write(exchange, status, "{\"error\":\"boom\"}");
                return;
            }
            JsonNode node = json.readTree(request);
            String method = node.get("method") == null ? "" : node.get("method").asString();
            JsonNode id = node.get("id");
            switch (method) {
                case "initialize" -> respond(exchange, id,
                        "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}");
                case "tools/list" -> respond(exchange, id, """
                        {"tools":[{"name":"echo","description":"回声",
                          "inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}
                        """);
                case "tools/call" -> respond(exchange, id,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"echo: hello\"}]}");
                default -> {
                    // notifications/initialized 等通知没有 id，也没有响应体
                    if (id == null || id.isNull()) {
                        write(exchange, 202, "");
                    } else {
                        respond(exchange, id, "{}");
                    }
                }
            }
        }
    }

    private void respond(HttpExchange exchange, JsonNode id, String resultJson) throws IOException {
        String idText = id == null || id.isNull() ? "null" : id.toString();
        write(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":%s}".formatted(idText, resultJson));
    }

    private void write(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
