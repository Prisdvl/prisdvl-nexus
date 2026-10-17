package com.lky.nexus.app.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.agent.model.entity.ToolApprovalPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 工具审批 Mapper（V22）。 */
@Mapper
public interface ToolApprovalMapper extends BaseMapper<ToolApprovalPO> {

    /**
     * 审批决策的原子落定：{@code WHERE status='PENDING'} 是"只有一个决策能生效"的全部实现。
     *
     * <p>不用"先 select 判断 PENDING 再 update"：两者之间有窗口，重复提交审批会两次都读到
     * PENDING，于是同一个工具调用被执行两次——这正是 §六 第 10 条要防的事。
     */
    @Update("""
            UPDATE ai_tool_approval
               SET status = #{status}, decision_scope = #{scope}, decision_reason = #{reason},
                   decided_at = #{now}
             WHERE id = #{approvalId} AND status = 'PENDING'
            """)
    int decide(@Param("approvalId") Long approvalId,
               @Param("status") String status,
               @Param("scope") String scope,
               @Param("reason") String reason,
               @Param("now") LocalDateTime now);

    /** 取消/超时：把该 run 下所有尚未决策的审批一次作废（返回作废条数）。 */
    @Update("""
            UPDATE ai_tool_approval
               SET status = #{status}, decided_at = #{now}
             WHERE run_id = #{runId} AND status = 'PENDING'
            """)
    int cancelPending(@Param("runId") Long runId,
                      @Param("status") String status,
                      @Param("now") LocalDateTime now);
}
