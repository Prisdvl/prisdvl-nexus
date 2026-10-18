package com.lky.nexus.app.workflow.model.dto;

/** 内置工作流模板的清单项（不含图，图在实例化时才写进用户的草稿）。 */
public record WorkflowPresetResponse(
        String code,
        String name,
        String description,
        int nodeCount
) {
}
