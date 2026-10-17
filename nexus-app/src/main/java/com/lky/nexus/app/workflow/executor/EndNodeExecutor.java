package com.lky.nexus.app.workflow.executor;

import com.lky.nexus.app.workflow.WorkflowExecutionContext;
import com.lky.nexus.app.workflow.WorkflowNodeExecutor;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** END 节点：优先输出模板渲染结果；未配置时输出空对象。 */
@Component
public class EndNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public String type() {
        return "END";
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowExecutionContext context) {
        Object template = node.config().get("template");
        if (template == null || String.valueOf(template).isBlank()) {
            return Map.of();
        }
        return context.render(String.valueOf(template));
    }
}
