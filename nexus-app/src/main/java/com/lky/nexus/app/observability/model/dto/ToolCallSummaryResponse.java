package com.lky.nexus.app.observability.model.dto;

/**
 * 请求详情里的一次工具调用（从 {@code chat_message.metadata.toolCalls} 解析）。
 *
 * @param tool          工具编码
 * @param input         入参（原样，可能是 JSON 字符串）
 * @param resultSummary 结果摘要（服务端已摘要过，不是完整结果）
 */
public record ToolCallSummaryResponse(
        String tool,
        String input,
        String resultSummary) {
}
