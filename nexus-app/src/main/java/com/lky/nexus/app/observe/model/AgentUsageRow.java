package com.lky.nexus.app.observe.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * "按 Agent 聚合的用量"的<b>数据库投影</b>（{@link com.lky.nexus.app.observe.AgentUsageMapper} 的返回类型）。
 *
 * <p>为什么不是 record：MyBatis 映射到 record 走的是构造器自动映射，
 * 默认<b>按位置</b>匹配（{@code argNameBasedConstructorAutoMapping} 关着），
 * 六列里插一列就会静默错位。可变 POJO + setter 是按列名匹配的，改 SQL 不会把数据换位。
 * 对外的形状另有一个 record（{@link AgentUsageResponse}），这一层只服务于映射。
 *
 * <p>字段名与 SQL 别名（snake_case）靠 {@code map-underscore-to-camel-case} 对应。
 */
@Data
public class AgentUsageRow {

    private Long agentId;

    /** 该 Agent 的稳定标识；<b>连接不中时为 null</b>（唯一可能：agent 行被物理删除，见 AgentUsageMapper）。 */
    private String code;

    private String name;

    /** 该 Agent 是否已被删除（逻辑删除也算：它还在库里，但用户已经删掉了它）。 */
    private Boolean deleted;

    private Long messages;

    private Long failed;

    private LocalDateTime lastUsedAt;
}
