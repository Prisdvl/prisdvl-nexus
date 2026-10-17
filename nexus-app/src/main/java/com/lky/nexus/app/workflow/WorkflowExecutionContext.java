package com.lky.nexus.app.workflow;

import java.util.Map;

/** 单次工作流执行的共享上下文。节点输出放在这里，不把 Spring 上下文暴露给节点。 */
public record WorkflowExecutionContext(
        Long userId,
        Map<String, Object> inputs,
        Map<String, Object> nodeOutputs,
        WorkflowVariableResolver variableResolver
) {

    public Object variable(String expression) {
        return variableResolver.resolve(expression, nodeOutputs, inputs);
    }

    public String render(String template) {
        return variableResolver.render(template, nodeOutputs, inputs);
    }
}
