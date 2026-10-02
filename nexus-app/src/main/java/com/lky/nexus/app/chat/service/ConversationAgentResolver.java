package com.lky.nexus.app.chat.service;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.chat.model.ChatMode;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;

/**
 * "这个会话这次该用哪份 Agent 定义"（5.4 抽取）。
 *
 * <p><b>为什么要有这个类</b>：答案本来只写在 {@code ChatStreamServiceImpl} 里（私有方法），
 * 而 5.4 让非流式入口也得按模式解析 Agent。两份实现是这类项目里最难发现的一类 bug——
 * 「会话选了 DRAFT 的 Agent，流式报 5008、非流式却照跑」，而且不报错、只是行为不同。
 * 所以它被抽成一处，两条链路都调这里。
 *
 * <p><b>为什么它落在 {@code chat} 包而不是 {@code AgentService} 上</b>：
 * 这段逻辑的输入是一行<b>会话</b>（读它的 {@code agent_id}），是聊天的关注点；
 * 写进 {@code AgentService} 会让 {@code agent} 包反向依赖 {@code chat.model.entity}，
 * 而 {@code ChatStreamServiceImpl} 本来就依赖 {@code AgentService}——再加一条反向边就成环了。
 * 依赖方向因此保持单向：{@code chat → agent}。
 *
 * <p><b>两种来源，都不是代码里的兜底常量</b>：
 * <ul>
 *   <li>会话选定的 Agent —— 走 {@link AgentService#requireDefinition}，带归属与状态三道检查；</li>
 *   <li>没选（{@code agent_id} 为 null）—— 走 {@link AgentService#resolveDefault}，
 *       它是数据库里的真实一行，用户在界面上看得见、改得动。这正是 §六十七 要的：
 *       兜底也必须是可配置的资源，而不是写死的 if-else。</li>
 * </ul>
 *
 * <p><b>状态检查放在这里而不是"只看创建会话那一次"</b>：会话建好之后 Agent 可能被停用、
 * 或被退回草稿。那种情况必须<b>当下就报错</b>（5008 / 5009），而不是继续用一个
 * "已经不该用"的 Agent 回答——用户看到的应该是可操作的错误，而不是一个悄悄降级的答案。
 */
public final class ConversationAgentResolver {

    private ConversationAgentResolver() {
    }

    /**
     * 解析这次要用的 Agent 定义；<b>非 AGENT 模式返回 {@code null}</b>。
     *
     * <p>把"模式判断"也收进来，是为了让调用方无法只做对一半：调用方若自己判 mode、
     * 自己调 requireDefinition，就又多出一份"什么模式该解析 Agent"的规则。
     * 返回 null 而不是抛异常，是因为"PLAIN 会话没有 Agent"是正常状态，不是错误。
     */
    public static AgentDefinition resolve(AgentService agentService, Long userId,
                                          ChatConversationPO conversation, ChatMode mode) {
        if (mode != ChatMode.AGENT) {
            return null;
        }
        Long agentId = conversation.getAgentId();
        return agentId == null
                ? agentService.resolveDefault(userId)
                : agentService.requireDefinition(userId, agentId);
    }
}
