package com.lky.nexus.app.agent;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.agent.tool.AgentToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Runtime（平台化 §十七）：按一份 {@link AgentDefinition} 跑一次完整的"工具循环"。
 *
 * <p><b>本类是 Phase 4.1 之前那个写死配置的 {@code AgentService}</b>，改动都是
 * "把常量换成 Definition 里的字段"：
 * <ol>
 *   <li>{@code AgentPrompts.AGENT_SYSTEM} → {@code definition.systemPrompt()}；</li>
 *   <li>{@code Role.CHAT_AGENT} → {@code definition.modelRole()}；</li>
 *   <li>工具"扫该用户全部知识库" → 知识库范围随 {@code definition.kbIds()} 进入 ToolContext
 *       （空 = 不限制，仍是原来的行为）；</li>
 *   <li>（4.3）工具从"写死的 {@code ToolCallbacks.from(knowledgeSearchTool)}" → 由
 *       {@code AgentToolFactory} 按"用户启用中的工具 ∩ 该 Agent 的白名单"装配。</li>
 * </ol>
 *
 * <p><b>Agent Loop 本身一行没动</b>（§六十七 明文禁止推翻）：循环仍由 Spring AI 的
 * {@code ToolCallingManager} 执行——声明了工具就会自动完成"模型要工具 → 执行 → 结果回填 → 模型继续"。
 * 我们的职责仍是那三个"用户可控"点：可见（痕迹）、有界（框架轮数上限）、可降级（工具失败如实说明）。
 *
 * <p>与 {@link AgentService}（资源服务）的分工写在那个类的注释里：资源服务碰数据库、
 * 校验归属与状态，本类只认值对象。数据库 Entity 永远进不到这里——这是 §十七 的硬要求，
 * 也让"跑一次 Agent"这件事可以被单元测试直接驱动（构造一个 Definition 即可，不需要数据库）。
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final ChatClientFactory chatClientFactory;
    private final AgentToolFactory toolFactory;
    private final AgentService agentService;

    public AgentRuntime(ChatClientFactory chatClientFactory,
                        AgentToolFactory toolFactory,
                        AgentService agentService) {
        this.chatClientFactory = chatClientFactory;
        this.toolFactory = toolFactory;
        this.agentService = agentService;
    }

    /**
     * 把一份 Definition 装到一次请求上：<b>提示词 + 工具 + ToolContext（用户与检索范围）</b>。
     *
     * <p>抽出来是因为这里有<b>两条</b>真实的 Agent 执行链路：本类的一次性调用，以及
     * {@code ChatStreamServiceImpl} 的流式对话。两者若各写一份，将来加第二个工具时只改一处，
     * 症状会是"对话能用、流式不能用"（或反过来）——这正是本项目一路在防的那类静默不一致
     * （同源的教训见 {@code AgentContext} 的类注释）。
     */
    public ChatClient.ChatClientRequestSpec configure(ChatClient.ChatClientRequestSpec request,
                                                      Long userId,
                                                      AgentDefinition definition,
                                                      AgentContext.ToolCallListener listener) {
        return toolFactory.applyTo(
                request.system(definition.systemPrompt())
                        // userId 与 kbIds 一起进 ToolContext：这是工具拿"能查谁的库、能查哪些库"的
                        // 唯一通道，且不会发给模型（框架生成工具入参 schema 时跳过 ToolContext 参数）
                        .toolContext(AgentContext.of(userId, definition.kbIds(), listener)),
                userId, definition);
        // 装哪些工具交给 AgentToolFactory（4.3）：它读的是"该用户启用中的工具 ∩ 这个 Agent 的白名单"。
        // 这里原来写的是 ToolCallbacks.from(knowledgeSearchTool)（写死的唯一工具），
        // 4.3 之后工具的来源是注册表（见 decisions.md B.38）。
    }

    /**
     * 跑一次 Agent。
     *
     * @param userId     资源归属；工具据此决定"能查谁的库"（也是 ToolContext 的唯一用途）
     * @param definition 运行配置（提示词 / 模型角色 / 知识库范围）
     * @param question   用户问题
     */
    public AgentAnswer ask(Long userId, AgentDefinition definition, String question) {
        long start = System.currentTimeMillis();
        // 痕迹由监听器收进本次调用的私有列表：不共享状态，天然并发安全
        List<AgentContext.ToolCallRecord> trace = new ArrayList<>();
        String answer = configure(chatClientFactory.get(definition.modelRole()).prompt(),
                userId, definition, AgentContext.collector(trace))
                .user(question)
                .call()
                .content();

        log.info("Agent 回答 userId={} agent={} 问题「{}」→ 工具调用 {} 次，耗时 {} ms",
                userId, definition.describe(), question, trace.size(), System.currentTimeMillis() - start);
        return new AgentAnswer(answer, List.copyOf(trace), !trace.isEmpty());
    }

    /**
     * 用该用户的<b>内置默认 Agent</b>跑一次。
     *
     * <p>给三个老入口用：{@code POST /api/agent/ask}、MCP 的 {@code agent_ask} 工具、评测集执行。
     * 它们此前用的是写死的提示词与模型角色——现在换成"读默认 Agent 那一行"，
     * 因此用户在界面上改了默认 Agent 的提示词，这三个入口的行为会一起跟着变（这正是可配置的含义）。
     */
    public AgentAnswer askWithDefault(Long userId, String question) {
        return ask(userId, agentService.resolveDefault(userId), question);
    }
}
