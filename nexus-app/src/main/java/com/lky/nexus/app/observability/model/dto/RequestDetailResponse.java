package com.lky.nexus.app.observability.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单次请求的全链路（{@code GET /api/observability/requests/{requestId}}）。
 *
 * <p>三段拼起来：调用明细（{@code observability_call}，本次新增）+ 检索摘要与工具摘要
 * （{@code chat_message.metadata}，本来就有）。前者回答"模型花了多少"，后者回答
 * "它看到了什么、做了什么"——这正是 §10.1 里"单次调用全链路"的含义。
 *
 * <p>计数字段叫 {@code callCount} 而不是 {@code calls}：一次 Agent 运行会有多轮调用，
 * 而 {@code calls} 这个名字已经留给逐轮明细列表了。同名两个字段在 record 里是编译错误，
 * 在 JSON 里则会互相覆盖——取个不会歧义的名字比事后解释便宜。
 *
 * @param requestId  请求 ID
 * @param scene      首个调用声明的场景
 * @param model      首个有模型名的调用
 * @param startedAt  首次调用时间
 * @param endedAt    末次调用时间
 * @param callCount  模型调用轮数
 * @param tokensIn   prompt token 合计
 * @param tokensOut  completion token 合计
 * @param tokens     两者之和
 * @param latencyMs  模型耗时合计
 * @param firstTokenMs 首轮首 Token 延迟
 * @param success    是否全部成功
 * @param errorCode  失败时的业务码
 * @param calls      逐轮明细（按时间升序）
 * @param retrieval  检索摘要（没有记录时 {@code found = false}）
 * @param toolCalls  工具调用摘要（没有时为空的列表）
 */
public record RequestDetailResponse(
        String requestId,
        String scene,
        String model,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        long callCount,
        long tokensIn,
        long tokensOut,
        long tokens,
        long latencyMs,
        Long firstTokenMs,
        boolean success,
        Integer errorCode,
        List<CallItemResponse> calls,
        RetrievalSummaryResponse retrieval,
        List<ToolCallSummaryResponse> toolCalls) {
}
