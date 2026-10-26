package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.dto.McpToolInfo;
import com.lky.nexus.common.api.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * <p>实现上的三个必要动作：
 * <ol>
 *   <li>stdout 必须<b>逐行</b>读（协议就是行分隔的）；</li>
 *   <li>stderr 必须<b>排空</b>（子进程写满 stderr 缓冲区会把自己卡死，症状是"调用永远超时"），
 *       而且要<b>留最后一段</b>——命令写错时真正的答案（{@code npm ERR! / unknown option}）在 stderr 里，
 *       丢掉它等于把用户扔进瞎猜；</li>
 *   <li>读线程看到 EOF 必须<b>立刻叫醒</b>等待方：否则"进程已退出"会被误报成"调用超时"，
 *       而这两件事的自救动作完全相反（一个该改命令，一个该调大超时）。</li>
 * </ol>
 *
 * <p>另外：{@code npx}/{@code uvx} 这类启动器会再拉一层真进程，所以 {@link #close()}
 * 必须杀<b>整棵进程树</b>，只杀直接子进程会留下一堆孤儿 node。
 */
public final class StdioMcpClient implements McpClient {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** 读线程结束时投给队列的哨兵：让等待方立刻知道"不会再有响应了"，而不是干等到超时。 */
    private static final String EOF = "\u0000__mcp_eof__";

    /** stderr 只留最后这么多字符：够看清 npm ERR! / 启动失败原因，又不会把内存吃满。 */
    private static final int STDERR_TAIL_LIMIT = 2000;

    private final JsonMapper json;
    private final String command;
    private final Duration timeout;
    private final Duration startupTimeout;
    private final AtomicLong ids = new AtomicLong(1);
    private final Object stderrLock = new Object();
    private final StringBuilder stderrTail = new StringBuilder();

    private Process process;
    private BufferedWriter stdin;
    private BlockingQueue<String> lines;
    private boolean initialized;
    private volatile boolean exited;
    private volatile Integer exitCode;

    public StdioMcpClient(JsonMapper json, String command, Duration timeout, Duration startupTimeout) {
        this.json = json;
        this.command = command;
        this.timeout = timeout;
        this.startupTimeout = startupTimeout;
    }

    @Override
    public List<McpToolInfo> listTools() {
        initializeIfNeeded();
        JsonNode result = roundTrip("tools/list", null, timeout);
        return parseTools(result);
    }

    @Override
    public String callTool(String toolName, String argumentsJson) {
        initializeIfNeeded();
        ObjectNode params = json.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", parseArguments(argumentsJson));
        return McpToolOutput.text(roundTrip("tools/call", params, timeout));
    }

    /**
     * 关掉子进程<b>整棵树</b>。
     *
     * <p>顺序是：先杀后代（npx 拉的 node、node 拉的 python…）→ 再 destroy 父进程 →
     * 宽容 2 秒 → 还活着就 destroyForcibly，最后再扫一遍后代。
     * 只 destroy 父进程的话，npx 退出后 node 会被 init 收养继续跑，任务管理器里就是一堆孤儿。
     */
    @Override
    public void close() {
        Process target = process;
        if (target == null) {
            return;
        }
        process = null;
        killTree(target);
    }

    /** 仅供测试：断言 close() 之后进程树真的死了（不留僵尸）。 */
    public ProcessHandle handleForTest() {
        Process target = process;
        return target == null ? null : target.toHandle();
    }

    /** 仅供测试：close() 之前先抓一份 {@link Process}，用来在关闭后检查整棵进程树。 */
    public Process processForTest() {
        return process;
    }

    private static void killTree(Process target) {
        killDescendants(target);
        target.destroy();
        try {
            if (!target.waitFor(2, TimeUnit.SECONDS)) {
                target.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        } finally {
            // 父进程死后再扫一遍：这期间可能还有刚被 fork 出来的后代
            killDescendants(target);
        }
    }

    private static void killDescendants(Process target) {
        try {
            target.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (RuntimeException ignored) {
                    // 已经自己退出了 / 不属于我们：都无所谓
                }
            });
        } catch (RuntimeException ignored) {
            // descendants() 在进程已回收时会抛，说明本来就没得杀
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
        // 握手单独用启动超时：npx 首次冷启动要下载包，拿"单次调用超时"卡它会打不过就报超时
        roundTrip("initialize", params, startupTimeout);
        write(objectNode("notifications/initialized", null, false));
        initialized = true;
    }

    private void start() {
        List<String> argv = tokenize(command);
        if (argv.isEmpty()) {
            throw new McpException(ErrorCode.MCP_STDIO_DISABLED, "stdio 传输需要一段可执行的命令行");
        }
        try {
            this.process = new ProcessBuilder(argv).start();
        } catch (IOException ex) {
            throw new McpException("无法启动 MCP 子进程（%s）：%s".formatted(argv.get(0), ex.getMessage()), ex);
        }
        this.exited = false;
        this.exitCode = null;
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
            } finally {
                markExited();
            }
        }, "mcp-stdio-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        // stderr 必须排空，否则子进程写到缓冲区满就直接卡死；同时留尾巴给"启动失败"当证据
        Thread errThread = new Thread(() -> {
            try (BufferedReader errorReader =
                         new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException ignored) {
                // 同上
            }
        }, "mcp-stdio-stderr");
        errThread.setDaemon(true);
        errThread.start();
    }

    /** 读线程收尾：记下退出码并把哨兵塞进队列，让正在等响应的人立刻失败而不是等到超时。 */
    private void markExited() {
        exited = true;
        Process target = process;
        if (target != null) {
            try {
                exitCode = target.exitValue();
            } catch (IllegalThreadStateException ignored) {
                // 还没完全退出：退出码留给下一次 read
            }
        }
        BlockingQueue<String> queue = lines;
        if (queue != null) {
            queue.offer(EOF);
        }
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            stderrTail.append(line).append('\n');
            int overflow = stderrTail.length() - STDERR_TAIL_LIMIT;
            if (overflow > 0) {
                stderrTail.delete(0, overflow);
            }
        }
    }

    private String stderrTailText() {
        synchronized (stderrLock) {
            return stderrTail.toString().trim();
        }
    }

    private JsonNode roundTrip(String method, ObjectNode params, Duration perCall) {
        long id = ids.getAndIncrement();
        if (exited) {
            throw processExited(method);
        }
        write(objectNode(method, params, true, id));
        long deadline = System.nanoTime() + perCall.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new McpException(ErrorCode.MCP_CALL_TIMEOUT,
                        "MCP 子进程在 %d ms 内没有响应（%s）".formatted(perCall.toMillis(), method));
            }
            String line;
            try {
                line = lines.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new McpException(ErrorCode.MCP_CALL_TIMEOUT, "等待 MCP 子进程响应时被中断（%s）".formatted(method), ex);
            }
            if (line == null) {
                continue;
            }
            if (EOF.equals(line)) {
                throw processExited(method);
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
                throw new McpException(ErrorCode.MCP_JSONRPC_ERROR,
                        "MCP server 拒绝了这次调用（%s）：%s".formatted(method, describeError(error)));
            }
            JsonNode result = node.get("result");
            return result == null ? json.createObjectNode() : result;
        }
    }

    /** 子进程已经退出：把退出码与 stderr 尾巴一起给人看，否则"失败"两个字没法修。 */
    private McpException processExited(String method) {
        Integer code = exitCode;
        String tail = stderrTailText();
        String message = "MCP 子进程已退出（调用 %s 时，退出码 %s）".formatted(method, code == null ? "未知" : code)
                + (tail.isEmpty() ? "；没有输出错误信息，检查命令行是否拼错" : "：" + tail);
        return new McpException(ErrorCode.MCP_PROCESS_EXITED, message);
    }

    private String describeError(JsonNode error) {
        JsonNode code = error.get("code");
        JsonNode message = error.get("message");
        if (message != null && !message.isNull()) {
            return "%s %s".formatted(code == null || code.isNull() ? "" : "[" + code.asInt() + "]",
                    message.asString()).trim();
        }
        return error.toString();
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
        BufferedWriter writer = stdin;
        if (writer == null) {
            throw new McpException(ErrorCode.MCP_SERVER_UNAVAILABLE, "MCP 子进程还没启动，无法写入");
        }
        try {
            writer.write(json.writeValueAsString(payload));
            writer.write('\n');
            writer.flush();
        } catch (IOException ex) {
            // 写失败最常见的原因就是进程已经没了：这时报"进程退出"比报"IO 错误"有用得多
            if (exited) {
                throw processExited(payload.path("method").asString(""));
            }
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
