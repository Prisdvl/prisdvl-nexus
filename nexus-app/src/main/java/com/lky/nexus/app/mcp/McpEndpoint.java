package com.lky.nexus.app.mcp;

import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.agent.KnowledgeSearchTool;
import com.lky.nexus.common.api.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
 * 工具内部复用已两度 live 验证的 KnowledgeSearchTool / AgentService。
 */
@RestController
public class McpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final KnowledgeSearchTool knowledgeSearchTool;
    private final AgentService agentService;
    private final JsonMapper jsonMapper;

    public McpEndpoint(KnowledgeSearchTool knowledgeSearchTool,
                       AgentService agentService,
                       JsonMapper jsonMapper) {
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.agentService = agentService;
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
        ObjectNode searchSchema = jsonMapper.createObjectNode();
        searchSchema.put("type", "object");
        ObjectNode searchProps = searchSchema.putObject("properties");
        searchProps.putObject("query").put("type", "string");
        searchProps.putObject("topK").put("type", "integer");
        searchSchema.putArray("required").add("query");

        ObjectNode askSchema = jsonMapper.createObjectNode();
        askSchema.put("type", "object");
        askSchema.putObject("properties").putObject("question").put("type", "string");
        askSchema.putArray("required").add("question");

        ObjectNode tools = jsonMapper.createObjectNode();
        tools.putArray("tools")
                .add(toolNode("knowledge_search",
                        "在用户的个人知识库中检索笔记片段（混合检索，不可用时自动降级为关键词匹配）。",
                        searchSchema))
                .add(toolNode("qa_ask",
                        "基于用户知识库回答一个问题（模型先检索再回答，结果带来源）。",
                        askSchema));
        return tools;
    }

    private ObjectNode toolNode(String name, String description, ObjectNode schema) {
        ObjectNode tool = jsonMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params, Long userId) {
        String toolName = params.path("name").asString("");
        JsonNode arguments = params.path("arguments");
        Long previous = AgentContext.begin(userId);
        try {
            String text;
            switch (toolName) {
                case "knowledge_search" -> {
                    String query = arguments.path("query").asString("");
                    int topK = arguments.path("topK").asInt(5);
                    if (query.isBlank()) {
                        return error(id, -32602, "knowledge_search 缺少 query 参数");
                    }
                    text = knowledgeSearchTool.search(query, topK);
                }
                case "qa_ask" -> {
                    String question = arguments.path("question").asString("");
                    if (question.isBlank()) {
                        return error(id, -32602, "qa_ask 缺少 question 参数");
                    }
                    text = agentService.ask(userId, question).answer();
                }
                default -> {
                    return error(id, -32601, "Unknown tool: " + toolName);
                }
            }
            ObjectNode content = jsonMapper.createObjectNode();
            ObjectNode textItem = jsonMapper.createObjectNode();
            textItem.put("type", "text");
            textItem.put("text", text);
            content.putArray("content").add(textItem);
            content.put("isError", false);
            return result(id, content);
        } finally {
            AgentContext.end(previous);
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