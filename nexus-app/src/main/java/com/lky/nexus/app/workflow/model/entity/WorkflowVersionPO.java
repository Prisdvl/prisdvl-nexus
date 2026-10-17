package com.lky.nexus.app.workflow.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流不可变版本（wf_version，V23）。 */
@Data
@TableName("wf_version")
public class WorkflowVersionPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workflowId;

    private Integer versionNo;

    private Integer schemaVersion;

    private String graphJson;

    private String graphHash;

    private String status;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;
}
