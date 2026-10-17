package com.lky.nexus.app.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按节点类型查找执行器。 */
@Component
public class WorkflowNodeExecutorRegistry {

    private final Map<String, WorkflowNodeExecutor> executors;

    public WorkflowNodeExecutorRegistry(List<WorkflowNodeExecutor> executors) {
        this.executors = executors.stream()
                .collect(Collectors.toUnmodifiableMap(WorkflowNodeExecutor::type, Function.identity()));
    }

    public WorkflowNodeExecutor require(String type) {
        WorkflowNodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw new IllegalArgumentException("不支持的节点类型: " + type);
        }
        return executor;
    }
}
