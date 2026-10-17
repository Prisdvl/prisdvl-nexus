package com.lky.nexus.app.workflow.executor;

import com.lky.nexus.app.workflow.WorkflowExecutionContext;
import com.lky.nexus.app.workflow.WorkflowNodeExecutor;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

/** START 节点：把工作流输入作为节点输出。 */
@Component
public class StartNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public String type() {
        return "START";
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowExecutionContext context) {
        return context.inputs();
    }
}
