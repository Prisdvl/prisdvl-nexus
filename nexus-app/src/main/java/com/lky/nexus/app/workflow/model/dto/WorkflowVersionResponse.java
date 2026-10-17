package com.lky.nexus.app.workflow.model.dto;

import java.time.LocalDateTime;

/** 工作流版本摘要。 */
public record WorkflowVersionResponse(
        Long id,
        Integer versionNo,
        String status,
        String graphHash,
        LocalDateTime createdAt,
        LocalDateTime publishedAt
) {
}
