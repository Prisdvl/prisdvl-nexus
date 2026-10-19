package com.lky.nexus.app.agent.model.dto;

import java.time.LocalDateTime;

/**
 * 工具调用审计行（对外，roadmap 待办 #7 的第 2 条）。
 *
 * <p>审计要回答的是「模型想做什么、我们放行了没有」，因此这里给的是<b>已有的事实字段</b>，
 * 不做二次解释：{@code permission} 是调用当时库里生效的权限，{@code status} 是被拒绝 /
 * 被取消 / 等待超时 / 成功这些终局（被拦下来的调用同样落行，见 {@code ToolInvocationPO}）。
 *
 * <p>{@code argumentsDigest} / {@code resultDigest} 存进库时就已经是脱敏摘要，
 * 不是原始参数与原始结果——查询接口直接透出摘要，不还原、也不额外脱敏。
 */
public record ToolInvocationResponse(
        Long id,
        Long runId,
        Long stepId,
        String toolCode,
        String permission,
        String status,
        Long approvalId,
        Integer errorCode,
        String argumentsDigest,
        String resultDigest,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
