package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行步骤（{@code ai_agent_run_step}，V22）。
 *
 * <p><b>不存隐藏思维链原文</b>：{@code THINK} 只是一条阶段标记（"开始规划 / 第 n 轮"），
 * {@code TOOL_CALL} / {@code OBSERVATION} 存的是**已脱敏**的入参与输出摘要
 * （见 {@code AgentTraceGuards}）。模型内部推理属于运行时数据，不属于审计数据。
 */
@Data
@TableName("ai_agent_run_step")
public class AgentRunStepPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    /** 运行内递增序号，唯一键 {@code (run_id, seq)} 保证顺序不会因重放而乱。 */
    private Integer seq;

    /** THINK / TOOL_CALL / OBSERVATION / ANSWER */
    private String stepType;

    private String toolCode;

    private String inputDigest;

    private String outputDigest;

    /** OK / FAILED / DENIED / CANCELLED */
    private String status;

    private LocalDateTime createdAt;
}
