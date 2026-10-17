package com.lky.nexus.app.workflow.model.dto;

import java.util.Map;

/** 手动运行工作流请求。 */
public record RunWorkflowRequest(Map<String, Object> inputs) {

    public RunWorkflowRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }
}
