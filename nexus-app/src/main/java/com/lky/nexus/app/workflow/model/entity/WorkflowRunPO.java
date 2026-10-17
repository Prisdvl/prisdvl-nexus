package com.lky.nexus.app.workflow.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流运行（wf_run，V23）。 */
@Data
@TableName("wf_run")
public class WorkflowRunPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workflowId;

    private Long versionId;

    private Long userId;

    private String status;

    private String triggerType;

    private String inputJson;

    private String outputJson;

    private Integer errorCode;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
