package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import com.lky.nexus.app.security.CredentialCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * 按 {@code transport} 造一个 {@link McpClient}。
 *
 * <p>凭据解密<b>只在这一个地方</b>发生：调用方（probe / 工具执行）拿不到明文，
 * 也就不可能把它写进日志或响应。解密失败（换过 KEK、密文损坏）会带着明确原因抛出去，
 * 而不是"连不上"——这两种故障的排查方向完全不同。
 *
 * <p><b>stdio 默认关闭</b>：它会把配置里的命令行真的执行起来。默认关闭意味着
 * "打开它"是一个明确的部署决定（{@code nexus.mcp.stdio-enabled=true}），
 * 而不是某个用户在网页上填一行字就能换来的能力。
 */
@Component
public class McpClientFactory {

    private final JsonMapper json;
    private final CredentialCipher cipher;
    private final Duration timeout;
    private final boolean stdioEnabled;

    public McpClientFactory(JsonMapper json,
                            CredentialCipher cipher,
                            @Value("${nexus.mcp.timeout-ms:15000}") long timeoutMs,
                            @Value("${nexus.mcp.stdio-enabled:false}") boolean stdioEnabled) {
        this.json = json;
        this.cipher = cipher;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 1000));
        this.stdioEnabled = stdioEnabled;
    }

    public McpClient create(McpServerPO server) {
        String transport = server.getTransport() == null ? "http" : server.getTransport().trim().toLowerCase();
        return switch (transport) {
            case "stdio" -> createStdio(server);
            case "http", "streamable-http", "" -> createHttp(server);
            default -> throw new McpException("不支持的传输方式：%s（可选 http / stdio）".formatted(server.getTransport()));
        };
    }

    private McpClient createHttp(McpServerPO server) {
        if (server.getUrl() == null || server.getUrl().isBlank()) {
            throw new McpException("streamable HTTP 传输需要填写 endpoint URL");
        }
        return new StreamableHttpMcpClient(json, server.getUrl().trim(), authorizationOf(server), timeout);
    }

    private McpClient createStdio(McpServerPO server) {
        if (!stdioEnabled) {
            throw new McpException("本机 stdio 传输已在服务端关闭（nexus.mcp.stdio-enabled=false）："
                    + "它会执行服务器上的命令行，只能由部署者开启");
        }
        if (server.getCommand() == null || server.getCommand().isBlank()) {
            throw new McpException("stdio 传输需要填写命令行");
        }
        return new StdioMcpClient(json, server.getCommand().trim(), timeout);
    }

    private String authorizationOf(McpServerPO server) {
        if (server.getAuthHeaderCipher() == null || server.getAuthHeaderCipher().isBlank()) {
            return null;
        }
        return cipher.decrypt(server.getAuthHeaderCipher());
    }
}
