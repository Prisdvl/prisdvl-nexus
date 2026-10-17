package com.lky.nexus.app.workflow;

import com.lky.nexus.app.workflow.model.WorkflowNode;

/** 节点执行器。一个实现只负责一种节点类型。 */
public interface WorkflowNodeExecutor {

    String type();

    Object execute(WorkflowNode node, WorkflowExecutionContext context) throws Exception;
}
