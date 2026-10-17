package com.lky.nexus.app.workflow.executor;

import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.workflow.WorkflowExecutionContext;
import com.lky.nexus.app.workflow.WorkflowNodeExecutor;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;

/** LLM 节点：复用 ChatClientFactory，不直接依赖具体模型 SDK。 */
@Component
public class LlmNodeExecutor implements WorkflowNodeExecutor {

    private final ChatClientFactory chatClientFactory;

    public LlmNodeExecutor(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public String type() {
        return "LLM";
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowExecutionContext context) {
        String systemPrompt = context.render(String.valueOf(node.config().getOrDefault("systemPrompt", "")));
        String userPrompt = context.render(String.valueOf(node.config().get("userPrompt")));
        String modelRole = String.valueOf(node.config().getOrDefault("modelRole", "chat.default"));
        var request = chatClientFactory.get(modelRole, context.userId()).prompt()
                .user(userPrompt);
        if (!systemPrompt.isBlank()) {
            request = request.system(systemPrompt);
        }
        Object temperature = node.config().get("temperature");
        if (temperature instanceof Number number) {
            request = request.options(ChatOptions.builder().temperature(number.doubleValue()));
        }
        String answer = request.call().content();
        return Map.of("text", answer == null ? "" : answer);
    }
}
