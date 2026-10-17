package com.lky.nexus.app.workflow.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流节点执行轨迹（wf_node_run，V23）。 */
@Data
@TableName("wf_node_run")
public class WorkflowNodeRunPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private String nodeId;

    private String nodeType;

    private String status;

    private Integer attempt;

    private String inputJson;

    private String outputJson;

    private Integer errorCode;

    private String errorMessage;

    private Long latencyMs;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
