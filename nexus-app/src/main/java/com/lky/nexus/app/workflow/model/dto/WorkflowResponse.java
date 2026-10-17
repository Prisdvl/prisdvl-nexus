package com.lky.nexus.app.workflow.model.dto;

import com.lky.nexus.app.workflow.model.WorkflowEdge;
import com.lky.nexus.app.workflow.model.WorkflowNode;

import java.time.LocalDateTime;
import java.util.List;

/** 工作流详情。 */
public record WorkflowResponse(
        Long id,
        String name,
        String description,
        String status,
        Long draftVersionId,
        Long publishedVersionId,
        Integer draftVersionNo,
        Integer publishedVersionNo,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
