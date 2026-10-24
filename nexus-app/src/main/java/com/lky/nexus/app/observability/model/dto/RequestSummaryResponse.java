package com.lky.nexus.app.observability.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 请求列表里的一行（{@code GET /api/observability/requests}）。
 *
 * <p><b>一行 = 一个 {@code request_id}</b>，不是一次模型调用：工具循环会让一次请求穿过
 * 模型多次，把它们拆成多行会让"这次问答到底花了多少"永远对不上。
 *
 * @param requestId   请求 ID（与响应头 {@code X-Request-Id} 一致）
 * @param scene       首个调用声明的场景
 * @param model       首个有模型名的调用
 * @param startedAt   该请求第一次模型调用的时间
 * @param endedAt     最后一次模型调用的时间
 * @param calls       该请求的模型调用轮数
 * @param tokens      该请求的 token 合计
 * @param latencyMs   该请求的模型耗时合计（不是墙钟：不含检索与工具执行）
 * @param firstTokenMs 首轮调用的首 Token 延迟（没有流式调用时为 {@code null}）
 * @param success     该请求的调用是否全部成功
 * @param errorCode   失败时最早一个非空错误码
 * @param scenes      该请求涉及的全部场景（工具循环可能跨场景，如 chat + agent）
 */
public record RequestSummaryResponse(
        String requestId,
        String scene,
        String model,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        long calls,
        long tokens,
        long latencyMs,
        Long firstTokenMs,
        boolean success,
        Integer errorCode,
        List<String> scenes) {
}
