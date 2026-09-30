package com.lky.nexus.app.agent;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 服务（Phase 5.1）：一次完整的"工具循环"。
 *
 * <p>循环由 Spring AI 的 ToolCallingManager 执行（声明了工具就会自动完成
 * "模型要工具 → 执行 → 结果回填 → 模型继续"，并有轮数上限），
 * 我们的职责是三个"用户可控"点：
 * <ol>
 *   <li><b>可见</b>：每次工具调用都写入 {@link AgentContext} 痕迹并随回答返回；</li>
 *   <li><b>有界</b>：工具循环受框架轮数上限约束，不会无限自调用；</li>
 *   <li><b>可降级</b>：检索基础设施不可用/失败时工具返回明确说明，模型如实回答而不是硬编。
 * </ol>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClientFactory chatClientFactory;
    private final KnowledgeSearchTool knowledgeSearchTool;

    public AgentService(ChatClientFactory chatClientFactory, KnowledgeSearchTool knowledgeSearchTool) {
        this.chatClientFactory = chatClientFactory;
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    public AgentAnswer ask(Long userId, String question) {
        long start = System.currentTimeMillis();
        // 痕迹由监听器收进本次调用的私有列表：不共享状态，天然并发安全
        List<AgentContext.ToolCallRecord> trace = new ArrayList<>();
        String answer = chatClientFactory.get(AiModelProperties.Role.CHAT_AGENT)
                .prompt()
                .system(AgentPrompts.AGENT_SYSTEM)
                .user(question)
                .toolContext(AgentContext.of(userId, AgentContext.collector(trace)))
                .tools(ToolCallbacks.from(knowledgeSearchTool))
                .call()
                .content();

        log.info("Agent 回答 userId={} 问题「{}」→ 工具调用 {} 次，耗时 {} ms",
                userId, question, trace.size(), System.currentTimeMillis() - start);
        return new AgentAnswer(answer, List.copyOf(trace), !trace.isEmpty());
    }
}