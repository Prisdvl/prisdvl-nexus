package com.lky.nexus.app.workflow.executor;

import com.lky.nexus.app.workflow.WorkflowExecutionContext;
import com.lky.nexus.app.workflow.WorkflowNodeExecutor;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** TEMPLATE 节点：把变量组合成文本。 */
@Component
public class TemplateNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public String type() {
        return "TEMPLATE";
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowExecutionContext context) {
        Object template = node.config().get("template");
        if (template == null) {
            return Map.of("text", "");
        }
        return Map.of("text", context.render(String.valueOf(template)));
    }
}
