package com.lky.nexus.app.agent;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;

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

    /** 系统提示的核心约束：工具优先——与知识库相关的问题必须先用工具，再答。 */
    private static final String SYSTEM_PROMPT = """
            你是一个基于用户个人知识库的助手。

            规则：
            1. 用户的问题涉及知识库内容时，**必须先调用 knowledge_search 检索**，再根据结果回答；
            2. 回答不能超出检索到的资料，并注明资料来源；检索不到就如实说"没有找到相关资料"；
            3. 不要用你自己的既有知识回答知识库相关的问题（用户要的是他笔记里的内容）；
            4. 如果检索结果明显与问题无关，可以换关键词再检索一次，但每次提问最多调用 3 次工具；
            5. 用中文回答，条理清晰。
            """;

    private final ChatClientFactory chatClientFactory;
    private final KnowledgeSearchTool knowledgeSearchTool;

    public AgentService(ChatClientFactory chatClientFactory, KnowledgeSearchTool knowledgeSearchTool) {
        this.chatClientFactory = chatClientFactory;
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    public AgentAnswer ask(Long userId, String question) {
        long start = System.currentTimeMillis();
        Long previous = AgentContext.begin(userId);
        try {
            String answer = chatClientFactory.get(AiModelProperties.Role.CHAT_DEFAULT)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(question)
                    .tools(ToolCallbacks.from(knowledgeSearchTool))
                    .call()
                    .content();

            List<AgentContext.ToolCallRecord> trace = AgentContext.trace();
            log.info("Agent 回答 userId={} 问题「{}」→ 工具调用 {} 次，耗时 {} ms",
                    userId, question, trace.size(), System.currentTimeMillis() - start);
            return new AgentAnswer(answer, trace, !trace.isEmpty());
        } finally {
            AgentContext.end(previous);
        }
    }
}