package com.lky.nexus.app.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流执行引擎（Phase 6.2 核心）：顺序执行、失败中止、状态全程可观测。
 *
 * <p>刻意做小且纯：不依赖任何服务/基础设施，因此能用纯单元测试钉住语义。
 * 真实的步骤（统计、嵌入、建索引…）由调用方以 {@link Step} 传入——
 * 引擎只负责"顺序、中止、计时、状态机"，这是编排里最需要确定性、最容易测坏的部分。
 */
public final class WorkflowEngine {

    private WorkflowEngine() {
    }

    /** 一个可执行的工作流步骤。 */
    public interface Step {
        String name();

        /**
         * 执行这一步。
         *
         * @return 结果（ok=false 视作失败并中止后续）；
         *         抛异常也视作失败（引擎会接住并记录 message）
         */
        Result execute() throws Exception;
    }

    public record Result(boolean ok, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    public record StepResult(String name, String status, long durationMs, String message) {
    }

    /**
     * 顺序执行：任一步骤失败则中止，其余标记 SKIPPED（写明原因）。
     *
     * @return 每步的结果（含状态/耗时/消息），顺序与输入一致
     */
    public static List<StepResult> run(List<Step> steps) {
        List<StepResult> results = new ArrayList<>(steps.size());
        boolean failed = false;
        for (Step step : steps) {
            if (failed) {
                results.add(new StepResult(step.name(), "SKIPPED", 0, "依赖的前置步骤失败，未执行"));
                continue;
            }
            long start = System.nanoTime();
            try {
                Result result = step.execute();
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                results.add(new StepResult(step.name(),
                        result.ok() ? "SUCCESS" : "FAILED", durationMs, result.message()));
                if (!result.ok()) {
                    failed = true;
                }
            } catch (Exception ex) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                results.add(new StepResult(step.name(), "FAILED", durationMs,
                        ex.getMessage() == null ? "未知异常" : ex.getMessage()));
                failed = true;
            }
        }
        return results;
    }
}