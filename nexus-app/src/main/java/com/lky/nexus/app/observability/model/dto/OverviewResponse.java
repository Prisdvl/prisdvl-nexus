package com.lky.nexus.app.observability.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 观测总览（{@code GET /api/observability/overview}）。
 *
 * <p>所有数字都限定在 {@code [from, to]} 窗口内，且<b>只统计当前用户</b>的调用
 * （系统调用如重排没有 userId，不进任何人的总览）。
 *
 * @param windowHours     实际生效的窗口小时数（请求值被夹到 1..720）
 * @param from            窗口起点
 * @param to              窗口终点
 * @param calls           模型调用次数（<b>逐轮</b>计数，工具循环会记多次）
 * @param tokensIn        prompt token 合计
 * @param tokensOut       completion token 合计
 * @param tokens          两者之和
 * @param avgLatencyMs    平均耗时；没有调用时为 0
 * @param firstTokenMs    首 Token 平均延迟；窗口内没有流式调用（或供应商没给）时为 {@code null}
 * @param ragCalls        检索问答场景（{@code rag} / {@code rag.stream}）的调用数
 * @param toolCalls       窗口内工具调用次数（来自 {@code ai_tool_invocation}，不是模型调用）
 * @param failures        失败调用数（{@code success = 0}）
 * @param errorRate       失败率，0..1；没有调用时为 0
 * @param estimatedCost   估算成本（元）；<b>窗口内只要有一个模型没配价格就为 {@code null}</b>
 * @param costComplete    成本是否覆盖了窗口内全部模型
 * @param unpricedModels  缺价格的模型名（用于告诉使用者"为什么成本是 null"）
 */
public record OverviewResponse(
        int windowHours,
        LocalDateTime from,
        LocalDateTime to,
        long calls,
        long tokensIn,
        long tokensOut,
        long tokens,
        long avgLatencyMs,
        Long firstTokenMs,
        long ragCalls,
        long toolCalls,
        long failures,
        double errorRate,
        BigDecimal estimatedCost,
        boolean costComplete,
        List<String> unpricedModels) {
}
