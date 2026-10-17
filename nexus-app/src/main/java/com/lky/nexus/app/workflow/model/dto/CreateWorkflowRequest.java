package com.lky.nexus.app.workflow.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建工作流请求。 */
public record CreateWorkflowRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description
) {
}
