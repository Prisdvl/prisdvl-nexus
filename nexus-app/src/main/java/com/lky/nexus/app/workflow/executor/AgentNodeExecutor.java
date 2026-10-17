package com.lky.nexus.app.workflow.executor;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.app.agent.AgentRuntime;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.workflow.WorkflowExecutionContext;
import com.lky.nexus.app.workflow.WorkflowNodeExecutor;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** AGENT 节点：复用现有 AgentRuntime、工具白名单和审批机制。 */
@Component
public class AgentNodeExecutor implements WorkflowNodeExecutor {

    private final AgentService agentService;
    private final AgentRuntime agentRuntime;

    public AgentNodeExecutor(AgentService agentService, AgentRuntime agentRuntime) {
        this.agentService = agentService;
        this.agentRuntime = agentRuntime;
    }

    @Override
    public String type() {
        return "AGENT";
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowExecutionContext context) {
        Object agentIdValue = node.config().get("agentId");
        AgentDefinition definition = agentService.requireDefinition(
                context.userId(), Long.valueOf(String.valueOf(agentIdValue)));
        Object questionValue = node.config().getOrDefault("question", "{{ input.question }}");
        String question = questionValue instanceof String text && text.contains("{{")
                ? context.render(text) : String.valueOf(questionValue);
        var answer = agentRuntime.ask(context.userId(), definition, question);
        return Map.of("text", answer.answer(), "toolCalls", answer.toolCalls(), "usedTools", answer.usedTools());
    }
}
