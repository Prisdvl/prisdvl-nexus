package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.dto.McpToolInfo;
import com.lky.nexus.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * stdio 传输的<b>真机</b>验证：真的起子进程、真的说话、真的把它杀干净。
 *
 * <p>默认跳过（{@code MCP_STDIO_LIVE=true} 才跑）：它在测试机上执行命令，还要能访问 npm。
 * 单元测试里那种"起个假 server"的写法验不了这条路径——它要验证的恰恰是
 * <b>进程管理</b>：启动超时、子进程退出与超时怎么区分、杀进程树会不会留孤儿。
 * 这三件事都只有真起进程才能看见。
 *
 * <p>跑法（PowerShell）：
 * <pre>{@code
 * $env:MCP_STDIO_LIVE="true"; mvn -o -pl nexus-app -am "-Dtest=McpStdioLiveTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "MCP_STDIO_LIVE", matches = "true",
        disabledReason = "真机用例：会起子进程并访问 npm，只在显式设置 MCP_STDIO_LIVE=true 时运行")
class McpStdioLiveTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("官方 everything server：拉到工具、调出固定文本、关闭后不留子进程")
    void listsAndCallsEverythingServer() throws Exception {
        // 首次运行 npm 要下载包，所以给 20 秒启动超时（与 nexus.mcp.startup-timeout-ms 默认一致）
        StdioMcpClient client = new StdioMcpClient(json,
                "npx -y @modelcontextprotocol/server-everything",
                Duration.ofSeconds(20), Duration.ofSeconds(20));
        ProcessHandle handle = null;
        try {
            List<McpToolInfo> tools = client.listTools();
            assertThat(tools).extracting(McpToolInfo::name).contains("echo");
            McpToolInfo echo = tools.stream().filter(t -> "echo".equals(t.name())).findFirst().orElseThrow();
            // schema 必须原样带出来：模型靠它填参数，缺了就只能瞎猜
            assertThat(echo.inputSchema()).contains("message");

            String output = client.callTool("echo", "{\"message\":\"nexus-mcp-ok\"}");
            assertThat(output).isEqualTo("Echo: nexus-mcp-ok");
        } finally {
            Process target = client.processForTest();
            handle = target == null ? null : target.toHandle();
            client.close();
        }

        // 关闭后整棵进程树必须真的没了：npx 还会拉一层 node，只杀 npx 会留下孤儿
        if (handle != null) {
            ProcessHandle settled = handle.onExit().completeOnTimeout(handle, 5, TimeUnit.SECONDS).join();
            assertThat(settled.isAlive()).as("close() 之后子进程应当已经退出").isFalse();
            assertThat(handle.descendants().count()).as("不应当留下任何后代进程").isZero();
        }
    }

    @Test
    @DisplayName("子进程不吭声时是「超时」9205，不是「进程退出」")
    void reportsTimeoutWhenChildIgnoresInput() {
        StdioMcpClient client = new StdioMcpClient(json,
                "node -e \"setInterval(()=>{},1000)\"",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        try {
            assertThatThrownBy(client::listTools)
                    .isInstanceOf(McpException.class)
                    .satisfies(ex -> assertThat(((McpException) ex).getCode()).isEqualTo(ErrorCode.MCP_CALL_TIMEOUT));
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("子进程自己退出时是「进程退出」9206，而且带上 stderr 里的真实原因")
    void reportsProcessExitWithStderr() {
        StdioMcpClient client = new StdioMcpClient(json,
                "node -e \"console.error('boom: unrecognised flag'); process.exit(3)\"",
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try {
            assertThatThrownBy(client::listTools)
                    .isInstanceOf(McpException.class)
                    .satisfies(ex -> assertThat(((McpException) ex).getCode()).isEqualTo(ErrorCode.MCP_PROCESS_EXITED))
                    // 没有 stderr，"失败"两个字就没法修
                    .hasMessageContaining("boom: unrecognised flag");
        } finally {
            client.close();
        }
    }
}
