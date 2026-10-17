package com.lky.nexus.app.workflow.model.dto;

import java.time.LocalDateTime;

/** 工作流运行状态。 */
public record WorkflowRunResponse(
        Long id,
        Long workflowId,
        Long versionId,
        String status,
        String triggerType,
        Object input,
        Object output,
        Integer errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs
) {
}
