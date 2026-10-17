package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用的人工审批记录（{@code ai_tool_approval}，V22）。
 *
 * <p>"同一个 run 同时只有一个待决策审批"是这条链路的不变量：工具循环是顺序执行的，
 * 一次只有一个工具调用在等。决策用条件更新 {@code WHERE status='PENDING'}，
 * 因此重复提交、并发批准只有一个能生效。
 */
@Data
@TableName("ai_tool_approval")
public class ToolApprovalPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long userId;

    /** 关联的工具调用：决定"批准的是哪一次调用"，避免批准被套用到后续调用上。 */
    private Long invocationId;

    private String toolCode;

    private String argumentsDigest;

    private String reason;

    /** PENDING / APPROVED / DENIED / CANCELLED / EXPIRED */
    private String status;

    /** once / always / deny */
    private String decisionScope;

    private String decisionReason;

    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;
}
