package com.lky.nexus.app.agent.model.dto;

import com.lky.nexus.app.agent.model.entity.AgentRunPO;

import java.time.LocalDateTime;

/**
 * 运行详情（P0 §五）。
 *
 * <p>{@code status} 是六态状态机里的当前值；{@code pendingApproval} 在
 * {@code WAITING_APPROVAL} 时有值——客户端刷新后据此恢复"我在等什么"的界面，
 * 而不是靠本地记忆（刷新即丢的审批提示等于没有审批）。
 */
public record AgentRunResponse(
        String id,
        String status,
        String agentId,
        String conversationId,
        String question,
        String answer,
        String modelRole,
        Integer iterationCount,
        Integer maxIterations,
        Integer errorCode,
        String errorMsg,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime updatedAt,
        PendingApprovalResponse pendingApproval) {

    public static AgentRunResponse of(AgentRunPO run, PendingApprovalResponse pending) {
        return new AgentRunResponse(
                asString(run.getId()),
                run.getStatus(),
                asString(run.getAgentId()),
                asString(run.getConversationId()),
                run.getQuestion(),
                run.getAnswer(),
                run.getModelRole(),
                run.getIterationCount(),
                run.getMaxIterations(),
                run.getErrorCode(),
                run.getErrorMsg(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getUpdatedAt(),
                pending);
    }

    private static String asString(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
