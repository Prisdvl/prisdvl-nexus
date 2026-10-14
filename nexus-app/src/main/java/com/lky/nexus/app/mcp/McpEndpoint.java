package com.lky.nexus.app.mcp;

import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.AgentRuntime;
import com.lky.nexus.app.agent.tool.ExecutingToolCallback;
import com.lky.nexus.app.agent.tool.ToolCatalog;
import com.lky.nexus.app.agent.tool.ToolRegistry;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP Server（Phase 7，最小实现）。
 *
 * <p><b>为什么手写而不是引 spring-ai-starter-mcp-server</b>：MCP 的 JSON-RPC 契约本身很薄
 * （initialize / tools/list / tools/call / ping），自己实现一层薄代理，能力的边界完全可控制，
 * 也符合本项目"不把框架当黑盒"的一贯基调。协议细节见 modelcontextprotocol.io。
 *
 * <p><b>身份模型（2026-10-01 起与全站一致）</b>：与其它所有端点一样要
 * {@code Authorization: Bearer <JWT>}，用户身份取自令牌的 {@code sub}
 * （{@link CurrentUser#requireId()}）。此前这条路径靠 `X-Nexus-User` 请求头自证身份，
 * 于是"任何人能访问该端口"就等于"任何人能读任意用户的笔记、用他的 Agent 花钱"——
 * 那正是本项目在其余 43 处用 {@code requireId} 挡住的那件事（见 decisions.md B.42）。
 * 想只在本机使用，请把服务绑到 127.0.0.1；<b>隔离靠网络边界，不靠请求头</b>。
 * 工具内部复用已两度 live 验证的 KnowledgeSearchTool / AgentRuntime。
 *
 * <p><b>4.3 起 tools/list 与 tools/call 都从 {@link ToolCatalog} 来</b>（§1.11 第 2 条：消灭第二份
 * 硬编码）。此前这里手写着 {@code knowledge_search} 的名字、描述与 JSON Schema，还直接调
 * {@code knowledgeSearchTool.search(...)}——实现改了描述不改，模型/MCP 客户端看到的就是过期信息。
 * 现在名字、描述、schema 一律由 {@code @Tool} 方法反射而来，本类只负责搬运与协议外壳。
 *
 * <p><b>工具权限（5.9 八）</b>：{@code tools/list} 与 {@code tools/call} 都走
 * {@link ToolRegistry#enabledFor}——与 Agent 装配同一个判定，因此"列不出来的工具也调不动"。
 * 用户停用某个工具后，{@code tools/list} 不再返回它，{@code tools/call} 回一个明确的拒绝
 * （业务码 5004，见 {@link #permissionDenied}）。此前这条直调路径<b>完全没有</b>权限判定：
 * 界面上关掉的开关只对 Agent 生效，对 MCP 直调不生效——那正是"开关看起来生效"的假象。
 *
 * <p><b>两处如实说明（不假装）</b>：
 * <ol>
 *   <li>{@code qa_ask} <b>不在</b>注册表里——它不是 {@code @Tool} 方法，而是"跑一次默认 Agent"
 *       的入口（本机 MCP 客户端的一个便利工具），因此它的描述与 schema 仍写在这里。
 *       它的<b>权限与 Agent 完全一致</b>：内部走 {@code AgentRuntime.askWithDefault}，
 *       装配时同样按用户启停过滤，所以停用检索工具后 {@code qa_ask} 仍会回答、只是不再检索
 *       （这一点由 {@code AGENT_TOOLS_EMPTY} 计数与 WARN 留痕，不静默）；</li>
 *   <li>工具返回值被框架 JSON 序列化过一次（{@code String} 也会带引号），发给 MCP 客户端前
 *       要拆回原文——见 {@link #unwrapQuotedJson(String)}，那里记着实测依据。</li>
 * </ol>
 */
@RestController
public class McpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    /** 自定义 JSON-RPC 错误码：工具被该用户停用（理由见 {@link #permissionDenied}）。 */
    private static final int PERMISSION_DENIED_CODE = -32003;

    /** 非 @Tool 的便利入口：跑一次用户的默认 Agent（见类注释第 1 条）。 */
    private static final String QA_ASK = "qa_ask";

    private final ToolCatalog toolCatalog;
    /** 用户级权限（5.9 八）：与 Agent 装配共用同一个判定，工具调用的"能不能用"只有一个答案。 */
    private final ToolRegistry toolRegistry;
    private final AgentRuntime agentRuntime;
    private final ObservabilityRegistry observability;
    private final JsonMapper jsonMapper;

    /** 注入 MCP 端点所需的各项依赖，由 Spring 完成。 */
    public McpEndpoint(ToolCatalog toolCatalog,
                       ToolRegistry toolRegistry,
                       AgentRuntime agentRuntime,
                       ObservabilityRegistry observability,
                       JsonMapper jsonMapper) {
        this.toolCatalog = toolCatalog;
        this.toolRegistry = toolRegistry;
        this.agentRuntime = agentRuntime;
        this.observability = observability;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode mcp(@RequestBody JsonNode request) {
        // 身份在**进入方法之前**就由安全过滤器链定好了（/mcp 不再放行匿名），
        // 这里取的是令牌里的 sub。取不到只可能是"没走过滤器"这种误用，会抛 2001
        Long userId = CurrentUser.requireId();
        try {
            String method = request.path("method").asString("");
            JsonNode id = request.path("id");
            // 没有 id 的多为通知（notifications/initialized），按 MCP 惯例只回空
            if (id.isMissingNode() || id.isNull()) {
                return jsonMapper.createObjectNode();
            }
            return switch (method) {
                case "initialize" -> result(id, initializeResult());
                case "ping" -> result(id, jsonMapper.createObjectNode());
                case "tools/list" -> result(id, toolsList(userId));
                case "tools/call" -> toolsCall(id, request.path("params"), userId);
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception ex) {
            // 不把异常原文回给调用方（与 GlobalExceptionHandler 的兜底同一口径）：
            // 异常消息里可能带 SQL 片段、内部类名、下游地址等，而它们对客户端毫无用处。
            // 需要定位时靠日志里的堆栈 + 这一行 requestId，不靠响应体
            String requestId = StringUtils.hasText(RequestIdHolder.current())
                    ? RequestIdHolder.current() : "-";
            log.error("MCP 请求处理失败 userId={} requestId={}", userId, requestId, ex);
            ObjectNode err = jsonMapper.createObjectNode();
            err.put("code", -32603);
            err.put("message", "服务内部错误（requestId=" + requestId + "）");
            return jsonMapper.createObjectNode()
                    .put("jsonrpc", "2.0").put("id", -1).set("error", err);
        }
    }

    // ------------------------------------------------------------------

    private ObjectNode initializeResult() {
        ObjectNode caps = jsonMapper.createObjectNode();
        caps.putObject("tools").put("listChanged", false);
        ObjectNode info = jsonMapper.createObjectNode();
        info.put("name", "prisdvl-nexus");
        info.put("version", "0.0.1");
        return jsonMapper.createObjectNode()
                .put("protocolVersion", PROTOCOL_VERSION)
                .set("capabilities", caps)
                .set("serverInfo", info);
    }

    private ObjectNode toolsList(Long userId) {
        ObjectNode tools = jsonMapper.createObjectNode();
        ArrayNode array = tools.putArray("tools");
        // 顺序 = 注册顺序（ToolCatalog 刻意保序）：MCP 客户端看到的能力清单与界面上一致。
        // 只列**该用户启用中**的工具（5.9 八）：用户停用了 knowledge_search，就不能把它
        // 当可用工具报给客户端——否则客户端会拿着一个"看起来能用"的名字去调，
        // 然后拿到一句权限拒绝，那比一开始不列出来更让人费解
        for (ToolCatalog.Registered tool : toolRegistry.enabledFor(userId)) {
            // schema 原样是反射出来的 JSON 字符串，这里只为套进 JSON-RPC 的树里而解析一次，
            // 不做任何改写——改一个字就成了第二份描述
            array.add(toolNode(tool.code(), tool.description(), jsonMapper.readTree(tool.jsonSchema())));
        }
        array.add(toolNode(QA_ASK,
                "基于用户知识库回答一个问题（模型先检索再回答，结果带来源）。",
                qaAskSchema()));
        return tools;
    }

    /** {@code qa_ask} 的入参 schema（它不是 @Tool 方法，没有反射可依赖，只能写在这里）。 */
    private ObjectNode qaAskSchema() {
        ObjectNode schema = jsonMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("question").put("type", "string");
        schema.putArray("required").add("question");
        return schema;
    }

    private ObjectNode toolNode(String name, String description, JsonNode schema) {
        ObjectNode tool = jsonMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params, Long userId) {
        String toolName = params.path("name").asString("");
        JsonNode arguments = params.path("arguments");

        String text;
        boolean failed = false;
        if (QA_ASK.equals(toolName)) {
            String question = arguments.path("question").asString("");
            if (question.isBlank()) {
                return error(id, -32602, "qa_ask 缺少 question 参数");
            }
            text = agentRuntime.askWithDefault(userId, question).answer();
        } else {
            ToolCatalog.Registered tool = toolCatalog.find(toolName).orElse(null);
            if (tool == null) {
                // "压根没有这个工具"与"有但被你停用了"必须分开报：前者是客户端写错了名字，
                // 后者是用户的开关生效了。合成一句话会让用户去查文档找不存在的工具名
                return error(id, -32601, "Unknown tool: " + toolName);
            }
            // 用户停用 = 拒绝执行（5.9 八）。与 tools/list 同一份判定（ToolRegistry.enabledFor），
            // 因此"列不出来"与"调不动"永远一致——只做一半（藏起来但照样能调）是更坏的谎
            if (toolRegistry.enabledFor(userId).stream()
                    .noneMatch(enabled -> enabled.code().equals(tool.code()))) {
                log.info("MCP 工具调用被拒：userId={}，工具 {} 已被该用户停用", userId, tool.code());
                return permissionDenied(id, tool.code());
            }
            // 参数原样交给工具：schema 是反射出来的，缺失/类型错的参数由实现自己报错，
            // 不再在这里手写一份"哪个参数必填"的判断（那也是第二份 schema）
            String input = arguments.isMissingNode() || arguments.isNull() ? "{}" : arguments.toString();
            // 与模型链路共用同一个执行壳：同样的计数、日志、失败措辞。
            // 用 invoke 而不是 call，是因为 MCP 的响应里有 isError，失败必须如实标出来
            ExecutingToolCallback.Outcome outcome = new ExecutingToolCallback(tool, observability)
                    // MCP 是外部直接调工具（不经过模型），上下文由我们显式构造
                    .invoke(input, AgentContext.toolContext(userId));
            text = unwrapQuotedJson(outcome.output());
            failed = outcome.failed();
        }

        ObjectNode content = jsonMapper.createObjectNode();
        ObjectNode textItem = jsonMapper.createObjectNode();
        textItem.put("type", "text");
        textItem.put("text", text);
        content.putArray("content").add(textItem);
        content.put("isError", failed);
        return result(id, content);
    }

    /**
     * 把"被 JSON 序列化过一次的文本"拆回原文。
     *
     * <p><b>为什么需要它（实测，不是猜的）</b>：Spring AI 的
     * {@code DefaultToolCallResultConverter.convert} 会把工具返回值<b>一律</b> JSON 序列化——
     * 连 {@code String} 也不例外（{@code javap -c} 看到的是
     * {@code JsonHelper.toJson(result, true)}）。于是工具方法返回
     * {@code 1+2*4 = 9}，回调给出的是 {@code "1+2*4 = 9"}（带引号的 JSON 字符串字面量）。
     *
     * <p>模型那边无所谓：它收到的是 JSON 字符串，读得懂。但 MCP 的 {@code content[].text}
     * 按协议是<b>给人的原文</b>，把引号一起发过去，客户端显示出来就是
     * {@code "1+2*4 = 9"}——看着像工具的 bug。所以这里只做一件事：结果是 JSON 字符串字面量
     * 就取它的文本值，否则原样透传（结构化工具将来返回对象/数组时，JSON 正是它该有的样子）。
     *
     * <p>拆不动（不是合法 JSON）时按原文返回：宁可多一对引号，也不能把结果弄丢。
     * 注意只对工具返回值用这一层——{@code qa_ask} 的答案是模型的自然语言，不是 JSON，不该被拆。
     */
    private String unwrapQuotedJson(String output) {
        try {
            JsonNode node = jsonMapper.readTree(output);
            return node.isString() ? node.asString() : output;
        } catch (Exception ex) {
            return output;
        }
    }

    private ObjectNode result(JsonNode id, ObjectNode value) {
        return jsonMapper.createObjectNode()
                .put("jsonrpc", "2.0").set("id", id).set("result", value);
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode err = jsonMapper.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        return jsonMapper.createObjectNode()
                .put("jsonrpc", "2.0").set("id", id).set("error", err);
    }

    /**
     * "这个工具被你停用了"的拒绝响应。
     *
     * <p>为什么是 JSON-RPC 的 {@code error} 而不是 {@code result.isError=true}：
     * 本类里 {@code isError} 的含义是"工具真的跑了、但失败了"（{@code Outcome.failed()}），
     * 而这里工具<b>一次都没跑</b>。把"没跑"混进"跑了但失败"，调用方就再也分不清
     * 是自己的开关生效了，还是检索真的出错了。
     *
     * <p>为什么用 {@code -32003} 而不是某个标准码：JSON-RPC 2.0 只规定了"方法不存在
     * （-32601）""参数不合法（-32602）"，<b>没有</b>"无权限"这一档
     * （-32000..-32099 是留给实现自定义的区间）。硬套 -32601 会把"被停用"说成"没这个工具"，
     * 让客户端去文档里找一个其实存在的名字。业务码 5004 同时放进 {@code data.businessCode}，
     * 便于机器识别，不必去解析中文文案。
     */
    private ObjectNode permissionDenied(JsonNode id, String toolCode) {
        ObjectNode err = jsonMapper.createObjectNode();
        err.put("code", PERMISSION_DENIED_CODE);
        err.put("message", "%s：%s 已被停用（业务码 %d）"
                .formatted(ErrorCode.TOOL_PERMISSION_DENIED.getMessage(), toolCode,
                        ErrorCode.TOOL_PERMISSION_DENIED.getCode()));
        ObjectNode data = jsonMapper.createObjectNode();
        data.put("businessCode", ErrorCode.TOOL_PERMISSION_DENIED.getCode());
        data.put("tool", toolCode);
        err.set("data", data);
        return jsonMapper.createObjectNode()
                .put("jsonrpc", "2.0").set("id", id).set("error", err);
    }
}