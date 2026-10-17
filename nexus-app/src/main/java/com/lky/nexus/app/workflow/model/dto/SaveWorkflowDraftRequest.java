package com.lky.nexus.app.workflow.model.dto;

import com.lky.nexus.app.workflow.model.WorkflowEdge;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 保存画布草稿请求。 */
public record SaveWorkflowDraftRequest(
        @NotNull List<WorkflowNode> nodes,
        @NotNull List<WorkflowEdge> edges
) {
    public SaveWorkflowDraftRequest {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
