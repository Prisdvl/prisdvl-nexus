package com.lky.nexus.app.automation.model.dto;

import java.time.LocalDateTime;

/**
 * 一次自动化运行的视图（{@code GET /api/automations/{id}/runs} 与 {@code POST .../run} 共用）。
 *
 * <p>失败时 {@code status=FAILED} 且 {@code errorCode}/{@code errorMessage} 必定非空——
 * 这是"失败要落字段"的对外承诺：前端不用去翻日志才知道为什么没跑成。
 *
 * @param id                运行 ID
 * @param automationId      所属任务
 * @param triggerType       MANUAL（立即跑）/ SCHEDULED（到点）
 * @param status            RUNNING / SUCCEEDED / FAILED
 * @param answer            Agent 回答；失败时 null
 * @param errorCode         失败业务码；成功时 null
 * @param errorMessage      失败原因；成功时 null
 * @param promptTokens      输入 token；未采集到为 null（不写 0）
 * @param completionTokens  输出 token；未采集到为 null
 * @param totalTokens       输入 + 输出；未采集到为 null
 * @param startedAt         开始时刻
 * @param finishedAt        结束时刻
 * @param durationMs        耗时（毫秒）
 */
public record AutomationRunResponse(
        Long id,
        Long automationId,
        String triggerType,
        String status,
        String answer,
        Integer errorCode,
        String errorMessage,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs) {
}
