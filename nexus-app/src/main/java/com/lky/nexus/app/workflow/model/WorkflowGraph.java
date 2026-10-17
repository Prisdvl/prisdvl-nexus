package com.lky.nexus.app.workflow.model;

import java.util.List;

/** 工作流图的持久化模型。位置仅用于画布回显，不参与执行语义。 */
public record WorkflowGraph(
        int schemaVersion,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges
) {
    public WorkflowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
