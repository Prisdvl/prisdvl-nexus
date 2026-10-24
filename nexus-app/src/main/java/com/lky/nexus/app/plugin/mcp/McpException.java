package com.lky.nexus.app.plugin.mcp;

/**
 * 与外部 MCP server 通信失败。
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常：调用点（probe、工具执行）
 * 都要把失败变成一句<b>能给人看</b>的说明，而不是在签名里层层声明。
 *
 * <p>消息里<b>不许带凭据</b>：这些文本会进 {@code last_error} 列、进日志、进页面。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
