package com.lky.nexus.app.workflow.model.dto;

import java.time.LocalDateTime;

/** 工作流节点执行详情。 */
public record WorkflowNodeRunResponse(
        Long id,
        String nodeId,
        String nodeType,
        String status,
        Integer attempt,
        Object input,
        Object output,
        Integer errorCode,
        String errorMessage,
        Long latencyMs,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
