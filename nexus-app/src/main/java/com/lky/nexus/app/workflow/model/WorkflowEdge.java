package com.lky.nexus.app.workflow.model;

/** 图中的连线；sourceHandle 用于条件分支的 true/false 端口。 */
public record WorkflowEdge(
        String id,
        String source,
        String target,
        String sourceHandle
) {
}
