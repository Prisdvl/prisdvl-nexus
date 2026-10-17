package com.lky.nexus.app.workflow.model.dto;

import java.util.List;

/** 草稿校验结果。 */
public record WorkflowValidationResponse(boolean valid, List<String> errors) {

    public static WorkflowValidationResponse ok() {
        return new WorkflowValidationResponse(true, List.of());
    }
}
