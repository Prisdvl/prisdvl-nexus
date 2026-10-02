package com.lky.nexus.app.observe;

import com.lky.nexus.app.observe.model.AgentUsageRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 按 Agent 聚合 {@code chat_message}（5.4）。<b>本接口只有只读查询</b>，不继承 BaseMapper——
 * 它读的不是某张表的实体，而是一张跨表读模型（{@code chat_message JOIN agent}），
 * 挂上 {@code BaseMapper} 只会换来一堆永远不会被调用的 CRUD 方法。
 *
 * <p>为什么放在 {@code observe} 包而不是 {@code chat.mapper}：这个查询属于观测看板，
 * 不是聊天链路的一部分。放在 chat 里会让"聊天模块"多出一个只为报表存在的跨表查询，
 * 以后改 chat_message 的人得同时想到报表。
 *
 * <p>三条刻意的写法：
 * <ol>
 *   <li><b>{@code user_id = #{userId}} 是硬条件</b>：这是多租户表，聚合更不能跨租户——
 *       漏掉它，用户会看到别人的 Agent 用量，而且数字看起来完全正常。
 *       V17 给这一列配的索引 {@code idx_msg_user_agent (user_id, agent_id, id)}
 *       就是为这条查询准备的（user_id 打头，正是为了让这个条件能被索引接住）；</li>
 *   <li><b>只统计助手消息</b>（{@code role = 'ASSISTANT'}）：只有助手消息有 agent_id
 *       （见 V17 的文件头——用户消息恒为 null，因为不是任何 Agent 产出的）。
 *       这里显式写出来而不是靠 {@code agent_id IS NOT NULL} 隐含，是为了让"统计口径"一眼可见；</li>
 *   <li><b>{@code LEFT JOIN} 而不是 {@code INNER JOIN}</b>：Agent 被删掉之后，它历史上跑过的
 *       那些消息不该跟着从报表里消失（"这个 Agent 以前用了多少"是个事实）。
 *       {@code deleted} 也一并取出来，让调用方能如实标注"已删除"而不是让它看起来还活着。</li>
 * </ol>
 *
 * <p>注意 {@code agent} 是<b>逻辑删除</b>表（{@code deleted} 列 + MyBatis-Plus 的
 * {@code @TableLogic}），但那个拦截只作用于 MyBatis-Plus 生成的 SQL；
 * 本方法是手写 SQL，会如实连到已删除的行——这正是这里想要的行为。
 *
 * <p>{@code failed} 用 {@code COUNT(CASE … THEN 1 END)} 而不是 {@code SUM(CASE … THEN 1 ELSE 0 END)}：
 * 后者在 MySQL 返回 DECIMAL、在 H2 返回 BIGINT，同一段代码在两个库上得到不同的 Java 类型；
 * COUNT 两边都是 BIGINT，省掉一处只会在测试里炸的差异。
 */
@Mapper
public interface AgentUsageMapper {

    @Select("""
            SELECT m.agent_id                                    AS agent_id,
                   a.code                                        AS code,
                   a.name                                        AS name,
                   a.deleted                                     AS deleted,
                   COUNT(*)                                      AS messages,
                   COUNT(CASE WHEN m.status = 'FAILED' THEN 1 END) AS failed,
                   MAX(m.created_at)                             AS last_used_at
            FROM chat_message m
            LEFT JOIN agent a ON a.id = m.agent_id
            WHERE m.user_id = #{userId}
              AND m.role = 'ASSISTANT'
              AND m.agent_id IS NOT NULL
            GROUP BY m.agent_id, a.code, a.name, a.deleted
            ORDER BY messages DESC, m.agent_id
            """)
    List<AgentUsageRow> selectAgentUsage(@Param("userId") Long userId);
}
