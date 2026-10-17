package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行（{@code ai_agent_run}，V22）。
 *
 * <p>状态机：{@code RUNNING → WAITING_APPROVAL → RUNNING → DONE/FAILED/MAX_ITERATIONS}，
 * 以及 {@code RUNNING/WAITING_APPROVAL → CANCELLED}。状态转换一律用
 * {@code UPDATE ... WHERE id=? AND status=?} 的条件更新完成——先读后写会在并发审批
 * 与取消同时到达时产生两次合法转换（"重复执行同一个工具调用"的温床）。
 */
@Data
@TableName("ai_agent_run")
public class AgentRunPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long conversationId;

    private Long agentId;

    private String question;

    /** RUNNING / WAITING_APPROVAL / DONE / FAILED / MAX_ITERATIONS / CANCELLED */
    private String status;

    private Integer maxIterations;

    private Integer iterationCount;

    private String modelRole;

    private String answer;

    private Integer errorCode;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime updatedAt;
}
