package com.lky.nexus.app.mcp;

import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.AgentRuntime;
import com.lky.nexus.app.agent.tool.ExecutingToolCallback;
import com.lky.nexus.app.agent.tool.ToolCatalog;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
 * <p><b>身份模型（刻意简单且如实）</b>：这是一个<b>个人本机</b>的 MCP 服务，
 * 不是公开 API——调用方通过 `X-Nexus-User` 头指定用户 id（本机信任模型，文档里如实说明）。
 * 工具内部复用已两度 live 验证的 KnowledgeSearchTool / AgentRuntime。
 *
 * <p><b>4.3 起 tools/list 与 tools/call 都从 {@link ToolCatalog} 来</b>（§1.11 第 2 条：消灭第二份
 * 硬编码）。此前这里手写着 {@code knowledge_search} 的名字、描述与 JSON Schema，还直接调
 * {@code knowledgeSearchTool.search(...)}——实现改了描述不改，模型/MCP 客户端看到的就是过期信息。
 * 现在名字、描述、schema 一律由 {@code @Tool} 方法反射而来，本类只负责搬运与协议外壳。
 *
 * <p><b>两处如实说明（不假装）</b>：
 * <ol>
 *   <li>{@code qa_ask} <b>不在</b>注册表里——它不是 {@code @Tool} 方法，而是"跑一次默认 Agent"
 *       的入口（本机 MCP 客户端的一个便利工具），因此它的描述与 schema 仍写在这里；</li>
 *   <li>用户对工具的"启停"<b>不作用于</b>这条直调路径：启停生效的地方是 Agent 装配
 *       （见 {@code AgentToolFactory}）。要让"停用即拒"在这里也成立，就得连
 *       {@code tools/call} 的拒绝一起做——那属于 P1 的权限一档，不做半个
 *       （只在 tools/list 里藏起来而 call 仍然能调，是更坏的谎）。
 *   <li>工具返回值被框架 JSON 序列化过一次（{@code String} 也会带引号），发给 MCP 客户端前
 *       要拆回原文——见 {@link #unwrapQuotedJson(String)}，那里记着实测依据。</li>
 * </ol>
 */
@RestController
public class McpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    /** 非 @Tool 的便利入口：跑一次用户的默认 Agent（见类注释第 1 条）。 */
    private static final String QA_ASK = "qa_ask";

    private final ToolCatalog toolCatalog;
    private final AgentRuntime agentRuntime;
    private final ObservabilityRegistry observability;
    private final JsonMapper jsonMapper;

    public McpEndpoint(ToolCatalog toolCatalog,
                       AgentRuntime agentRuntime,
                       ObservabilityRegistry observability,
                       JsonMapper jsonMapper) {
        this.toolCatalog = toolCatalog;
        this.agentRuntime = agentRuntime;
        this.observability = observability;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode mcp(@RequestBody JsonNode request,
                          @org.springframework.web.bind.annotation.RequestHeader(value = "X-Nexus-User",
                                  required = false) String nexusUser) {
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
                case "tools/list" -> result(id, toolsList());
                case "tools/call" -> toolsCall(id, request.path("params"), resolveUser(nexusUser));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception ex) {
            log.warn("MCP 请求处理失败：{}", ex.getMessage());
            ObjectNode err = jsonMapper.createObjectNode();
            err.put("code", -32603);
            err.put("message", ex.getMessage() == null ? "internal error" : ex.getMessage());
            return jsonMapper.createObjectNode()
                    .put("jsonrpc", "2.0").put("id", -1).set("error", err);
        }
    }

    // ------------------------------------------------------------------
    private Long resolveUser(String nexusUser) {
        if (nexusUser == null || nexusUser.isBlank()) {
            throw new IllegalArgumentException("缺少 X-Nexus-User 请求头（个人本机信任模型）");
        }
        try {
            return Long.valueOf(nexusUser.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("X-Nexus-User 必须是用户 id（数字）");
        }
    }

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

    private ObjectNode toolsList() {
        ObjectNode tools = jsonMapper.createObjectNode();
        ArrayNode array = tools.putArray("tools");
        // 顺序 = 注册顺序（ToolCatalog 刻意保序）：MCP 客户端看到的能力清单与界面上一致
        for (ToolCatalog.Registered tool : toolCatalog.all()) {
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
                return error(id, -32601, "Unknown tool: " + toolName);
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
}