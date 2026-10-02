package com.lky.nexus.app.observe.model;

import java.time.LocalDateTime;

/**
 * 一个 Agent 的用量（{@code GET /api/observability/agents} 的条目）。
 *
 * <p><b>它回答的问题</b>："我配的那些 Agent，到底哪个在被真的用？"——
 * 5.4 之前这个问题没有答案：消息上不记 {@code agentId}，只能靠会话上那个<b>活的</b>
 * 选择去猜，而那个值随时会被改，且会话不选 Agent 时是 null（连"内置默认在跑"都看不出来）。
 *
 * <p>{@code failed} 单独给出来，是因为"这个 Agent 被用了 100 次"和"被用了 100 次、其中 80 次失败"
 * 是两句完全不同的话，只看总数会得出相反的结论。
 */
public record AgentUsageResponse(
        Long agentId,
        /** 稳定标识；Agent 行已被物理删除时可能为 null。 */
        String code,
        String name,
        /**
         * 该 Agent 当前是否已被删除（含逻辑删除）。
         *
         * <p>为什么必须带这个标记：Agent 的删除是<b>逻辑删除</b>——行还在，名字还查得到，
         * 但用户已经把它删了。不标出来，报表上那些"已经删掉的 Agent"看起来和活的一模一样，
         * 用户会对着一个不存在的 Agent 排查问题。
         */
        boolean deleted,
        /** 该 Agent 产出过的助手消息数。 */
        long messages,
        /** 其中以失败收场的条数（状态 FAILED）。 */
        long failed,
        LocalDateTime lastUsedAt) {
}
