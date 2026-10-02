package com.lky.nexus.app.observe;

import com.lky.nexus.app.observe.model.AgentUsageResponse;
import com.lky.nexus.app.observe.model.AgentUsageRow;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 用量聚合（5.4）。
 *
 * <p>很薄：一条查询 + 把投影翻成对外形状。之所以还是单独一个类而不是塞进 controller，
 * 是因为这里有一处<b>不能省</b>的判断——join 不到 agent 行时怎么标名字（见下）。
 * 放在 controller 里，下一个想加导出/图表的人也只会去抄 controller。
 */
@Service
public class AgentUsageService {

    /**
     * Agent 行<b>物理</b>消失时的兜底显示名。
     *
     * <p>正常路径下取不到：Agent 的删除是逻辑删除，行还在、名字还查得到（那种情况会带上
     * {@code deleted=true} 由界面标注）。这句话兜的是"直接删库/手工清表"这类运维动作——
     * 那时宁可显示"已删除的 Agent"，也不该把那一行从报表里抹掉：用户会看到总数对不上，
     * 却找不到少掉的是谁。
     */
    private static final String UNKNOWN_NAME = "已删除的 Agent";

    private final AgentUsageMapper agentUsageMapper;

    public AgentUsageService(AgentUsageMapper agentUsageMapper) {
        this.agentUsageMapper = agentUsageMapper;
    }

    /** 按消息数倒序返回该用户的 Agent 用量；没有任何消息时是空列表（不是错误）。 */
    public List<AgentUsageResponse> usageByAgent(Long userId) {
        return agentUsageMapper.selectAgentUsage(userId).stream().map(this::toResponse).toList();
    }

    private AgentUsageResponse toResponse(AgentUsageRow row) {
        boolean missing = row.getCode() == null && row.getName() == null;
        return new AgentUsageResponse(
                row.getAgentId(),
                row.getCode(),
                missing ? UNKNOWN_NAME : row.getName(),
                // join 不中（行没了）与 deleted=1 都算"已删除"：对读报表的人来说是同一件事
                missing || Boolean.TRUE.equals(row.getDeleted()),
                nullToZero(row.getMessages()),
                nullToZero(row.getFailed()),
                row.getLastUsedAt());
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
