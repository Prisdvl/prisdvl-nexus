package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计（{@code ai_tool_invocation}，V22）。
 *
 * <p><b>被拒绝、被取消、等待超时的调用同样落一行</b>——审计要回答的是"模型想做什么、
 * 我们放行了没有"，只记成功调用等于把最需要复查的那部分抹掉。
 * {@code permission} 存执行当时库里实际生效的值，而不是代码声明，
 * 这样"某次调用为什么被拦"事后可复盘。
 */
@Data
@TableName("ai_tool_invocation")
public class ToolInvocationPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long stepId;

    private Long userId;

    private String toolCode;

    private String permission;

    private String argumentsDigest;

    private Long approvalId;

    /** PENDING_APPROVAL / SUCCEEDED / FAILED / DENIED / CANCELLED */
    private String status;

    private String resultDigest;

    private Integer errorCode;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
