package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.ToolCategory;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具执行壳：三件与工具实现无关的事——计数、把失败变成观察结果、以及<b>别把 ToolContext 弄丢</b>。
 *
 * <p>最后一条是本类存在的真正理由。实测（{@code javap -c} 读 Spring AI 2.0.1 的字节码）：
 * {@link ToolCallback#call(String, ToolContext)} 在接口里的默认实现只打一行日志就转调
 * {@link ToolCallback#call(String)}，而 {@code MethodToolCallback} 恰恰靠这个重载把 ToolContext
 * 注入工具方法。壳只实现无参那个重载的话，症状是"Agent 突然不检索了，一条报错都没有"——
 * 这里用"哪个重载被走到"的断言把它钉住，而不是指望下一个人记得。
 */
@DisplayName("ExecutingToolCallback：计数、失败回灌、ToolContext 透传")
class ExecutingToolCallbackTest {

    @Test
    @DisplayName("成功：结果原样返回，成功计数 +1")
    void successIsCounted() {
        ObservabilityRegistry observability = new ObservabilityRegistry();
        StubCallback stub = new StubCallback(null);

        ExecutingToolCallback.Outcome outcome = shell(stub, observability).invoke("1+1", null);

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.output()).isEqualTo("ok:1+1");
        assertThat(observability.snapshot()).containsEntry(ObservabilityRegistry.TOOL_CALL_SUCCEEDED, 1L);
        assertThat(observability.snapshot()).doesNotContainKey(ObservabilityRegistry.TOOL_CALL_FAILED);
    }

    @Test
    @DisplayName("失败：不往外抛，而是一句明确的观察结果；失败计数 +1（失败必须可计数）")
    void failureBecomesAnObservation() {
        ObservabilityRegistry observability = new ObservabilityRegistry();
        StubCallback stub = new StubCallback(new ArithmeticException("除法 的除数是零，没有定义"));

        ExecutingToolCallback.Outcome outcome = shell(stub, observability).invoke("1/0", null);

        assertThat(outcome.failed()).isTrue();
        // 措辞里要保住三样东西：哪个工具、为什么、下一步怎么办。
        // 模型看到这句话才可能"换个算法"或"如实说没做成"（§六十七 的可降级就落在这里）
        assertThat(outcome.output())
                .contains("boom")
                .contains("除数是零")
                .contains("如实告知用户");
        assertThat(observability.snapshot()).containsEntry(ObservabilityRegistry.TOOL_CALL_FAILED, 1L);
    }

    @Test
    @DisplayName("ToolContext 必须透传给实现：走的是两参重载，不是接口里那个只打日志的默认实现")
    void toolContextIsPassedThrough() {
        StubCallback stub = new StubCallback(null);
        ToolContext context = new ToolContext(Map.of("nexus.userId", 42L));

        String result = shell(stub, new ObservabilityRegistry()).call("{}", context);

        assertThat(result).isEqualTo("ok:{}");
        assertThat(stub.seenByTwoArgCall).isSameAs(context);
        // 无参重载只该在"确实没有上下文"时被走到（MCP 之外不该出现）
        assertThat(stub.sawOneArgCall).isFalse();
    }

    @Test
    @DisplayName("无参重载（没有上下文时）同样可用：两种调用形状都不能炸")
    void oneArgCallStillWorks() {
        StubCallback stub = new StubCallback(null);

        assertThat(shell(stub, new ObservabilityRegistry()).call("{}")).isEqualTo("ok:{}");
        assertThat(stub.sawOneArgCall).isTrue();
    }

    // ------------------------------------------------------------------

    private ExecutingToolCallback shell(ToolCallback callback, ObservabilityRegistry observability) {
        // 假工具声明 selfReports = true（"自己上报"这一侧）：本类只看壳自己那三件事。
        // "谁都不许少报一条痕迹"这个不变量由 ToolResourceTest 对真实注册表逐条断言（B.40）
        return new ExecutingToolCallback(new ToolCatalog.Registered(
                "boom", "炸弹工具", ToolCategory.OTHER, "测试用", "{\"type\":\"object\"}", callback, true),
                observability);
    }

    /** 记录"哪个重载被走到"的假实现：这是本测试要看的唯一东西。 */
    private static final class StubCallback implements ToolCallback {

        private final RuntimeException failure;
        private ToolContext seenByTwoArgCall;
        private boolean sawOneArgCall;

        StubCallback(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("boom")
                    .description("测试用")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            sawOneArgCall = true;
            return delegate(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            seenByTwoArgCall = toolContext;
            return delegate(toolInput);
        }

        private String delegate(String toolInput) {
            if (failure != null) {
                throw failure;
            }
            return "ok:" + toolInput;
        }
    }
}
