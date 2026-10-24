package com.lky.nexus.app.automation.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次自动化运行（表 {@code automation_run}）。
 *
 * <p>与 {@code wf_run} 同一套事实：状态、耗时、错误码 + 错误消息都落库，
 * 日志只做可观测性，不承担"事后能查到为什么失败"的责任。
 *
 * <p>没有 {@code deleted} 列：运行记录是审计事实，不做逻辑删除（删任务不删历史）。
 * token 三列可为 NULL——采集不到就如实留空，不写 0 假装知道。
 */
@Data
@TableName("automation_run")
public class AutomationRunPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long automationId;

    /** 冗余所有者：按用户查历史时免 join。 */
    private Long userId;

    /** MANUAL（立即跑）/ SCHEDULED（到点触发）。 */
    private String triggerType;

    /** RUNNING / SUCCEEDED / FAILED（{@code AutomationRunStatus}）。 */
    private String status;

    /** Agent 回答正文；失败时为空。 */
    private String answer;

    private Integer errorCode;

    private String errorMessage;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
