package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.dto.McpToolInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP over <b>stdio</b>（本机子进程，逐行 JSON-RPC）。
 *
 * <p><b>它是一把真刀</b>：配置里的一段命令行会被真的执行。所以这条路径默认关闭
 * （{@code nexus.mcp.stdio-enabled=false}，见 application.yml），打开它是部署者的决定，
 * 不是用户的决定——MCP 的 stdio 传输本来就只有这个语义，假装它安全才是更糟的谎。
 *
 * <p>实现上的两个必要动作：stdout 必须<b>逐行</b>读（协议就是行分隔的），
 * stderr 必须<b>排空</b>（子进程写满 stderr 缓冲区会把自己卡死，症状是"调用永远超时"）。
 */
public final class StdioMcpClient implements McpClient {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final JsonMapper json;
    private final String command;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong(1);

    private Process process;
    private BufferedWriter stdin;
    private BlockingQueue<String> lines;
    private boolean initialized;

    public StdioMcpClient(JsonMapper json, String command, Duration timeout) {
        this.json = json;
        this.command = command;
        this.timeout = timeout;
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
        return McpToolOutput.text(roundTrip("tools/call", params));
    }

    @Override
    public void close() {
        if (process == null) {
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            process = null;
        }
    }

    private void initializeIfNeeded() {
        if (initialized) {
            return;
        }
        start();
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", json.createObjectNode());
        ObjectNode clientInfo = json.createObjectNode();
        clientInfo.put("name", "nexus");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);
        roundTrip("initialize", params);
        write(objectNode("notifications/initialized", null, false));
        initialized = true;
    }

    private void start() {
        List<String> argv = tokenize(command);
        if (argv.isEmpty()) {
            throw new McpException("stdio 传输需要一段可执行的命令行");
        }
        try {
            this.process = new ProcessBuilder(argv).start();
        } catch (IOException ex) {
            throw new McpException("无法启动 MCP 子进程（%s）：%s".formatted(argv.get(0), ex.getMessage()), ex);
        }
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.lines = new LinkedBlockingQueue<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.put(line);
                }
            } catch (IOException | InterruptedException ignored) {
                // 进程被关掉是正常路径
            }
        }, "mcp-stdio-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        // stderr 必须排空，否则子进程写到缓冲区满就直接卡死
        Thread errThread = new Thread(() -> {
            try (var errorStream = process.getErrorStream()) {
                errorStream.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // 同上
            }
        }, "mcp-stdio-stderr");
        errThread.setDaemon(true);
        errThread.start();
    }

    private JsonNode roundTrip(String method, ObjectNode params) {
        long id = ids.getAndIncrement();
        write(objectNode(method, params, true, id));
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new McpException("MCP 子进程在 %d ms 内没有响应（%s）".formatted(timeout.toMillis(), method));
            }
            String line;
            try {
                line = lines.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new McpException("等待 MCP 子进程响应时被中断");
            }
            if (line == null) {
                continue;
            }
            JsonNode node = tryParse(line.trim());
            if (node == null) {
                continue;
            }
            JsonNode responseId = node.get("id");
            if (responseId == null || responseId.isNull() || responseId.asLong() != id) {
                // server 主动发的通知 / 别的请求：与本次 RPC 无关
                continue;
            }
            JsonNode error = node.get("error");
            if (error != null && !error.isNull()) {
                throw new McpException("MCP server 报错：" + error.toString());
            }
            JsonNode result = node.get("result");
            return result == null ? json.createObjectNode() : result;
        }
    }

    private ObjectNode objectNode(String method, ObjectNode params, boolean withId) {
        return objectNode(method, params, withId, ids.get());
    }

    private ObjectNode objectNode(String method, ObjectNode params, boolean withId, long id) {
        ObjectNode node = json.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (withId) {
            node.put("id", id);
        }
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node;
    }

    private void write(ObjectNode payload) {
        try {
            stdin.write(json.writeValueAsString(payload));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException ex) {
            throw new McpException("写入 MCP 子进程失败：%s".formatted(ex.getMessage()), ex);
        }
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
            JsonNode name = tool.get("name");
            if (name == null || name.isNull() || name.asString().isBlank()) {
                continue;
            }
            JsonNode description = tool.get("description");
            JsonNode schema = tool.get("inputSchema");
            parsed.add(new McpToolInfo(name.asString().trim(),
                    description == null || description.isNull() || description.asString().isBlank()
                            ? "MCP 工具 " + name.asString() : description.asString(),
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
            return json.createObjectNode();
        }
    }

    /** 按空白切分，但保留引号内的整体（{@code npx -y "some package"}）。 */
    static List<String> tokenize(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (char c : command.trim().toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }
}
