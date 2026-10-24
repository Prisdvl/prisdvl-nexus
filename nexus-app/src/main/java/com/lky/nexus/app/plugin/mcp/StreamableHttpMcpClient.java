package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.dto.McpToolInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP over <b>streamable HTTP</b>（一次 POST 一次 RPC）。
 *
 * <p>为什么是"POST + 可能的 SSE 响应"而不是长连接：协议本身就允许 server 用
 * {@code text/event-stream} 回一个 POST 的响应，也能用普通 JSON。这里两种都收，
 * 因为真实 server 两种都存在（官方 TypeScript SDK 默认响应 SSE）。
 *
 * <p>三个刻意的取舍：
 * <ul>
 *   <li><b>每次 RPC 一条连接</b>：不维护连接池。省下的那点握手时间换来的是
 *       "会话失效"这一类难查问题，不值得；</li>
 *   <li><b>{@code Mcp-Session-Id} 只在拿到时回传</b>：有些 server 不发，发了就必须带；</li>
 *   <li><b>错误消息里不带请求头</b>：{@code Authorization} 会出现在日志与 {@code last_error} 里，
 *       一旦带出去就是凭据泄露。</li>
 * </ul>
 */
public final class StreamableHttpMcpClient implements McpClient {

    /** 协议版本：写死一个我们真实支持的，而不是"跟随 server"——不兼容要显式报错。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final JsonMapper json;
    private final String endpoint;
    private final String authorization;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong(1);

    private String sessionId;
    private boolean initialized;

    public StreamableHttpMcpClient(JsonMapper json, String endpoint, String authorization, Duration timeout) {
        this.json = json;
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<McpToolInfo> listTools() {
        initializeIfNeeded();
        JsonNode result = roundTrip("tools/list", null);
        return parseTools(result);
    }

    @Override
    public String callTool(String toolName, String argumentsJson) {
        initializeIfNeeded();
        ObjectNode params = json.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", parseArguments(argumentsJson));
        JsonNode result = roundTrip("tools/call", params);
        return McpToolOutput.text(result);
    }

    @Override
    public void close() {
        // 无连接可关：每次 RPC 用完即弃
    }

    private void initializeIfNeeded() {
        if (initialized) {
            return;
        }
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", json.createObjectNode());
        ObjectNode clientInfo = json.createObjectNode();
        clientInfo.put("name", "nexus");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);
        roundTrip("initialize", params);
        // 握手完成的确认是通知（没有 id、没有响应体）；server 不接受也继续，
        // 因为 true 的判定是后面的 tools/list 能不能通
        notify("notifications/initialized");
        initialized = true;
    }

    private JsonNode roundTrip(String method, ObjectNode params) {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", ids.getAndIncrement());
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)));
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new McpException("连不上 MCP server（%s）：%s".formatted(endpoint, rootMessage(ex)), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpException("访问 MCP server 被中断");
        }
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> this.sessionId = id);
        if (response.statusCode() / 100 != 2) {
            throw new McpException("MCP server 返回 HTTP %d%s".formatted(response.statusCode(),
                    snippet(response.body())));
        }
        JsonNode payload = extractPayload(response);
        if (payload == null) {
            throw new McpException("MCP server 的响应不是可解析的 JSON-RPC 消息%s".formatted(snippet(response.body())));
        }
        JsonNode error = payload.get("error");
        if (error != null && !error.isNull()) {
            throw new McpException("MCP server 报错：" + errorMessage(error));
        }
        JsonNode result = payload.get("result");
        return result == null ? json.createObjectNode() : result;
    }

    private void notify(String method) {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)));
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 通知失败不影响后续：真正的判据是 tools/list
        }
    }

    /**
     * 从响应里取出那条 JSON-RPC 消息。
     *
     * <p>SSE 响应可能包含多条 {@code data:}（例如先来一条 progress 通知），所以取<b>最后一条</b>
     * 带 {@code result} / {@code error} 的；普通 JSON 响应直接解析。
     */
    private JsonNode extractPayload(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        if (!contentType.toLowerCase().contains("text/event-stream")) {
            return tryParse(body);
        }
        JsonNode last = null;
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            JsonNode candidate = tryParse(trimmed.substring("data:".length()).trim());
            if (candidate != null && (candidate.get("result") != null || candidate.get("error") != null)) {
                last = candidate;
            }
        }
        return last;
    }

    private JsonNode tryParse(String raw) {
        try {
            return json.readTree(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private List<McpToolInfo> parseTools(JsonNode result) {
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        List<McpToolInfo> parsed = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = text(tool.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String description = text(tool.get("description"));
            JsonNode schema = tool.get("inputSchema");
            parsed.add(new McpToolInfo(name.trim(),
                    description == null || description.isBlank() ? "MCP 工具 " + name : description,
                    schema == null ? "{\"type\":\"object\",\"properties\":{}}" : json.writeValueAsString(schema)));
        }
        return List.copyOf(parsed);
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(argumentsJson);
            return node != null && node.isObject() ? node : json.createObjectNode();
        } catch (RuntimeException ex) {
            // 模型给的入参不是 JSON 对象：按空参数发出去，让 server 用它自己的 schema 校验并报错，
            // 比在这里猜一个结构更诚实
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String errorMessage(JsonNode error) {
        if (error.isObject()) {
            String message = text(error.get("message"));
            JsonNode code = error.get("code");
            return code == null || code.isNull() ? String.valueOf(message) : "[%s] %s".formatted(code.asInt(), message);
        }
        return error.asString();
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return "：" + (flat.length() <= 200 ? flat : flat.substring(0, 200) + "…");
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
