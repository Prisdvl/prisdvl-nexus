package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import com.lky.nexus.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * stdio 的两道门（<b>不起 Spring、不起子进程</b>）。
 *
 * <p>为什么单独一个单元测试：这两道门的顺序与说法必须在<b>两种全局开关状态</b>下都成立，
 * 而 {@code @SpringBootTest} 的那套上下文只能固定一种 {@code nexus.mcp.stdio-enabled}。
 * 放在这里，才能断言"部署者关了 / 用户没勾"分别说哪句话——用户看不到正确的那一句，
 * 就只会去反复重试或者来问我们。
 *
 * <p>凭据解密在 stdio 路径上用不到，所以 {@code cipher} 传 null。
 */
class McpClientFactoryGateTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static McpServerPO server(String transport, String command, boolean stdioAllowed) {
        McpServerPO server = new McpServerPO();
        server.setTransport(transport);
        server.setCommand(command);
        server.setStdioAllowed(stdioAllowed ? 1 : 0);
        return server;
    }

    private McpClientFactory factory(boolean stdioEnabled) {
        return new McpClientFactory(json, null, 15_000, 20_000, stdioEnabled);
    }

    @Test
    @DisplayName("部署者关了 stdio：说「服务端关了」，而不是让用户去勾一个勾了也没用的开关")
    void globalSwitchOffWins() {
        McpClientFactory factory = factory(false);

        assertThatThrownBy(() -> factory.create(server("stdio", "npx -y x", true)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("stdio-enabled")
                .satisfies(ex -> assertThat(((McpException) ex).getCode())
                        .isEqualTo(ErrorCode.MCP_STDIO_DISABLED));
    }

    @Test
    @DisplayName("部署者开了、但这条 server 没签字：照样不起子进程，并说清去哪勾")
    void perServerConsentIsRequired() {
        McpClientFactory factory = factory(true);

        assertThatThrownBy(() -> factory.create(server("stdio", "npx -y x", false)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("显式允许")
                .satisfies(ex -> assertThat(((McpException) ex).getCode())
                        .isEqualTo(ErrorCode.MCP_STDIO_DISABLED));
    }

    @Test
    @DisplayName("两道门都开了但没写命令行：说「缺命令行」，而不是「连不上」")
    void missingCommandIsItsOwnFailure() {
        McpClientFactory factory = factory(true);

        assertThatThrownBy(() -> factory.create(server("stdio", "   ", true)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("命令行");
    }

    @Test
    @DisplayName("不认识的传输方式带 9203")
    void unknownTransportIsUnsupported() {
        McpClientFactory factory = factory(true);

        assertThatThrownBy(() -> factory.create(server("grpc", null, false)))
                .isInstanceOf(McpException.class)
                .satisfies(ex -> assertThat(((McpException) ex).getCode())
                        .isEqualTo(ErrorCode.MCP_TRANSPORT_UNSUPPORTED));
    }
}
