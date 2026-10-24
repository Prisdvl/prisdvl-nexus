package com.lky.nexus.common.observe;

/**
 * 调用明细的落库端口（{@code observability_call}）。
 *
 * <p>与 {@code ChatUsageRecorder} 同一套做法：<b>端口在 nexus-common，实现在 nexus-app</b>。
 * 产生方 {@code UsageMeteringAdvisor} 在 nexus-ai，只认这个接口，不认识 MyBatis 与表名。
 *
 * <p><b>为什么必须在实现方消化异常</b>：调用点是一次已经成功（或已经失败）的模型调用之后，
 * 观测留档是<b>附加价值</b>。它坏了不该把一次正常的回答变成 500——用户已经拿到答案了。
 *
 * <p>实现允许缺省：nexus-ai 单独被引用（纯 AI 层单测、mcp-server）时退化为 {@link #NOOP}。
 */
@FunctionalInterface
public interface CallTraceRecorder {

    /**
     * 记一条调用明细。
     *
     * @param trace 本次调用的事实；字段可否为 null 见 {@link CallTrace}
     */
    void record(CallTrace trace);

    /** 不落库的空实现：纯 AI 层与没有观测需求的装配用它。 */
    CallTraceRecorder NOOP = trace -> {
    };
}
