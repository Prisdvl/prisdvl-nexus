package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.common.api.ErrorCode;

/**
 * 与外部 MCP server 通信失败。
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常：调用点（probe、工具执行）
 * 都要把失败变成一句<b>能给人看</b>的说明，而不是在签名里层层声明。
 *
 * <p>消息里<b>不许带凭据</b>：这些文本会进 {@code last_error} 列、进日志、进页面。
 *
 * <p><b>必须带 {@link ErrorCode}</b>：工具调用失败要能区分
 * 「上游太慢（9205）」「子进程已经没了（9206）」「server 明确拒绝了这次调用（9207）」
 * 「连不上（5005）」——这四种事对应四种完全不同的自救动作，合成一句
 * 「模型调用失败」等于让用户去猜，531ffb6 已经踩过一次同类坑。
 */
public class McpException extends RuntimeException {

    /** 未特别说明的通信失败一律按「server 不可用」算（连不上、握手失败、响应看不懂）。 */
    private final ErrorCode code;

    public McpException(String message) {
        this(ErrorCode.MCP_SERVER_UNAVAILABLE, message, null);
    }

    public McpException(String message, Throwable cause) {
        this(ErrorCode.MCP_SERVER_UNAVAILABLE, message, cause);
    }

    public McpException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public McpException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
