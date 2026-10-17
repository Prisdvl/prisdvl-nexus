package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.app.agent.approval.ToolApprovalService;
import com.lky.nexus.app.agent.approval.ToolInvocationAuditService;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具装配（4.3，§十九 的 ToolFactory）：把一份 {@link AgentDefinition} 变成
 * "这次请求要交给模型哪些工具"。
 *
 * <p><b>它取代了什么（实测核对过，不是照抄设计稿）</b>：今天写死工具的地方其实是两处——
 * <ol>
 *   <li>{@code AgentRuntime.configure} 里的 {@code ToolCallbacks.from(knowledgeSearchTool)}；
 *       一次性调用与流式对话都走它（流式在 4.1 之后已改为委托 {@code configure}，
 *       所以"两份 Callbacks.from"那个说法不成立——{@code ChatStreamServiceImpl} 里留下的只是一个
 *       没人用的 import，已随本增量删掉）；</li>
 *   <li>{@code McpEndpoint}：手写了一份 {@code knowledge_search} 的名字、描述与 JSON Schema，
 *       并且直接调 {@code knowledgeSearchTool.search(...)} 绕开了整套装配。</li>
 * </ol>
 * 装配逻辑现在只此一处——{@code AgentRuntime} 类注释里担心的那类静默不一致
 * （"对话能用、流式不能用"）的根治办法不是让两份保持一致，而是只有一份。
 *
 * <p><b>0 个工具时不调用 {@code .tools(...)}</b>：不去赌框架对"空数组"的处理，
 * 而是让那条路径与"一个普通的不带工具的对话"完全等价——用户把所有工具都停用之后，
 * 行为就是明明白白的一句话模型调用。
 */
@Service
public class AgentToolFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentToolFactory.class);

    private final ToolRegistry registry;
    private final ObservabilityRegistry observability;
    private final ToolAuthorizationService authorization;
    private final ToolApprovalService approvalService;
    private final ToolInvocationAuditService auditService;

    public AgentToolFactory(ToolRegistry registry,
                            ObservabilityRegistry observability,
                            ToolAuthorizationService authorization,
                            ToolApprovalService approvalService,
                            ToolInvocationAuditService auditService) {
        this.registry = registry;
        this.observability = observability;
        this.authorization = authorization;
        this.approvalService = approvalService;
        this.auditService = auditService;
    }

    /** 装配出带执行壳的回调数组；顺序即工具登记顺序（提示词里给模型的顺序稳定，行为才可复现）。 */
    public ToolCallback[] assemble(Long userId, AgentDefinition definition) {
        List<ToolCatalog.Registered> tools = registry.resolveFor(userId, definition);
        ToolCallback[] callbacks = tools.stream()
                // 装配点永远带上授权闸门（P0）：模型的每一次工具调用都经过统一执行入口，
                // 不存在"某个 provider 的工具绕过审批"这条路——因为回调是这里造出来的
                .map(tool -> (ToolCallback) new ExecutingToolCallback(
                        tool, observability, authorization, approvalService, auditService))
                .toArray(ToolCallback[]::new);
        log.info("为 Agent {} 装配 {} 个工具：{}", definition.describe(), callbacks.length,
                tools.isEmpty() ? "（无）" : tools.stream().map(ToolCatalog.Registered::code).toList());
        return callbacks;
    }

    /** 装配并挂到请求上；没有工具时原样返回（见类注释）。 */
    public ChatClient.ChatClientRequestSpec applyTo(ChatClient.ChatClientRequestSpec request,
                                                    Long userId,
                                                    AgentDefinition definition) {
        ToolCallback[] tools = assemble(userId, definition);
        return tools.length == 0 ? request : request.tools(tools);
    }
}
