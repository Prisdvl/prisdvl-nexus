package com.lky.nexus.app.agent.model.dto;

import com.lky.nexus.app.agent.model.entity.ToolApprovalPO;

import java.time.LocalDateTime;

/**
 * 当前待决策审批（P0 §七 第 6 条：客户端重连/刷新后靠它恢复现场）。
 *
 * <p>{@code arguments} 是**已脱敏**的入参摘要——与 SSE 事件里的是同一份，
 * 两处不一致会让"刷新前后看到不同的参数"变成一个无法解释的怪现象。
 */
public record PendingApprovalResponse(
        String approvalId,
        String toolCode,
        String arguments,
        String reason,
        String status,
        LocalDateTime createdAt) {

    public static PendingApprovalResponse of(ToolApprovalPO approval) {
        if (approval == null) {
            return null;
        }
        return new PendingApprovalResponse(
                String.valueOf(approval.getId()),
                approval.getToolCode(),
                approval.getArgumentsDigest(),
                approval.getReason(),
                approval.getStatus(),
                approval.getCreatedAt());
    }
}
