package com.lky.nexus.app.workflow;

import com.lky.nexus.app.workflow.WorkflowEngine.Result;
import com.lky.nexus.app.workflow.WorkflowEngine.Step;
import com.lky.nexus.app.workflow.WorkflowEngine.StepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流引擎纯单元测试：顺序、中止、耗时、异常接管、SKIPPED 语义。
 *
 * <p>引擎语义是编排里最需要确定性、最容易测坏的部分，因此用纯测试钉死，
 * 不依赖任何服务/数据库。
 */
class WorkflowEngineTest {

    private Step named(String name, Result result) {
        return new Step() {
            public String name() {
                return name;
            }

            public Result execute() {
                return result;
            }
        };
    }

    @Test
    @DisplayName("全部成功：按顺序执行，状态 SUCCESS，耗时非负")
    void runsAllInOrder() throws Exception {
        List<Integer> order = new java.util.ArrayList<>();
        List<Step> steps = List.of(
                new Step() {
                    public String name() { return "a"; }

                    public Result execute() { order.add(1); return Result.ok("A"); }
                },
                new Step() {
                    public String name() { return "b"; }

                    public Result execute() { order.add(2); return Result.ok("B"); }
                });

        List<StepResult> results = WorkflowEngine.run(steps);

        assertThat(order).containsExactly(1, 2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).status()).isEqualTo("SUCCESS");
        assertThat(results.get(1).status()).isEqualTo("SUCCESS");
        assertThat(results.get(0).durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("失败即中止：后续步骤标记 SKIPPED 并写明原因")
    void abortsOnFailure() {
        List<Step> steps = List.of(
                named("ok", Result.ok("fine")),
                named("bad", Result.fail("数据源不可用")),
                named("never", Result.ok("不会执行")));

        List<StepResult> results = WorkflowEngine.run(steps);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).status()).isEqualTo("SUCCESS");
        assertThat(results.get(1).status()).isEqualTo("FAILED");
        assertThat(results.get(2).status()).isEqualTo("SKIPPED");
        assertThat(results.get(2).message()).contains("前置步骤失败");
    }

    @Test
    @DisplayName("步骤抛异常：按失败处理，并接住异常消息，不向调用方抛出")
    void catchesThrowsAsFailure() {
        List<Step> steps = List.of(new Step() {
            public String name() { return "boom"; }

            public Result execute() throws Exception {
                throw new IllegalStateException("网关 502");
            }
        });

        List<StepResult> results = WorkflowEngine.run(steps);

        assertThat(results.get(0).status()).isEqualTo("FAILED");
        assertThat(results.get(0).message()).contains("网关 502");
    }
}