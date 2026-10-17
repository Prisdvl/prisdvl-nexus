package com.lky.nexus.app.workflow.model;

import java.util.Map;

/** 图中的一个节点。节点配置由执行器按类型解释。 */
public record WorkflowNode(
        String id,
        String type,
        int version,
        Position position,
        Map<String, Object> config
) {
    public WorkflowNode {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public record Position(Double x, Double y) {
    }
}
