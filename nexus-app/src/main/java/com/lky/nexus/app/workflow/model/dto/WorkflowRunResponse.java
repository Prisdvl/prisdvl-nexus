package com.lky.nexus.app.workflow.model.dto;

import java.time.LocalDateTime;

/** 工作流运行状态。 */
public record WorkflowRunResponse(
        Long id,
        Long workflowId,
        Long versionId,
        String status,
        String triggerType,
        /** 重试自哪次运行；非重试为 null（V24 起可用）。 */
        Long retryOfRunId,
        Object input,
        Object output,
        Integer errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs
) {
}
