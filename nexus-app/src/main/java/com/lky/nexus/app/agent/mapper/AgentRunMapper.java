package com.lky.nexus.app.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** Agent 运行 Mapper（V22）。 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunPO> {

    /**
     * 条件更新：<b>只有当前状态等于 {@code expected} 时才改</b>。
     *
     * <p>返回影响行数，调用方据此判断"这次转换是不是只有我一个人成功"——
     * 并发审批/取消竞争时只有一次能拿到 1，其余拿到 0，是被拒而不是被吞掉。
     */
    @Update("""
            UPDATE ai_agent_run
               SET status = #{target}, updated_at = #{now}
             WHERE id = #{runId} AND status = #{expected}
            """)
    int compareAndSetStatus(@Param("runId") Long runId,
                            @Param("expected") String expected,
                            @Param("target") String target,
                            @Param("now") LocalDateTime now);
}
