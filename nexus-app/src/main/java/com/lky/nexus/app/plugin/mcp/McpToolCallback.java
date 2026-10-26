package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 一次 MCP 工具调用（{@code mcp__<slug>__<tool>}）。
 *
 * <p><b>它只管传输</b>：把参数原样发给外部 server、把回来的内容摊平成文本。
 * 授权、审批、留痕、异常话术都在 {@code ExecutingToolCallback} 那一层——所以这里
 * 失败了就让它抛出去，由执行壳统一翻译成"工具 X 执行失败：原因"。若在这里也拼一遍
 * 错误文案，同一次失败会有两套措辞，日志和用户看到的东西就对不上了。
 *
 * <p>唯一多做的一件事：把结果写进 {@code last_call_*}（页面上的"最近一次调用"）。
 * 写记录本身<b>不抛异常</b>，见 {@link McpCallRecorder}。
 *
 * <p>每次调用新建一个 client、用完即关：stdout/HTTP 连接不该跨请求复用
 * （MCP server 的状态属于它自己，我们只是调用方；长连接还要额外处理心跳与重连）。
 */
public final class McpToolCallback implements ToolCallback {

    private static final String DEFAULT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final McpClientFactory factory;
    private final McpServerPO server;
    private final String toolName;
    private final ToolDefinition definition;
    private final McpCallRecorder recorder;
    /** 界面/日志用的人话名字（模型看到的是 code）。 */
    private final String displayName;

    public McpToolCallback(McpClientFactory factory, McpServerPO server, String code, String toolName,
                           String displayName, String description, String jsonSchema) {
        this(factory, server, code, toolName, displayName, description, jsonSchema, null);
    }

    public McpToolCallback(McpClientFactory factory, McpServerPO server, String code, String toolName,
                           String displayName, String description, String jsonSchema,
                           McpCallRecorder recorder) {
        this.factory = factory;
        this.server = server;
        this.toolName = toolName;
        this.displayName = displayName;
        this.recorder = recorder;
        this.definition = ToolDefinition.builder()
                .name(code)
                .description(description == null || description.isBlank() ? "MCP 工具 " + toolName : description)
                .inputSchema(jsonSchema == null || jsonSchema.isBlank() ? DEFAULT_SCHEMA : jsonSchema)
                .build();
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        try (McpClient client = factory.create(server)) {
            String output = client.callTool(toolName, toolInput);
            record(null);
            return output;
        } catch (McpException ex) {
            // 带错误码记一笔：页面要能区分 9205 超时 / 9206 子进程退出 / 9207 JSON-RPC 拒绝
            record("[%d] %s".formatted(ex.getCode().getCode(), ex.getMessage()));
            // 原样抛出：翻译成人话是执行壳的活，这里改口会造成两套措辞
            throw ex;
        } catch (RuntimeException ex) {
            record("[%s] %s".formatted(ex.getClass().getSimpleName(), ex.getMessage()));
            throw ex;
        }
    }

    private void record(String error) {
        if (recorder != null) {
            recorder.record(server, toolName, error);
        }
    }
}
