package com.lky.nexus.app.workflow.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作流主表（wf_workflow，V23）。 */
@Data
@TableName("wf_workflow")
public class WorkflowPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long workspaceId;

    private String name;

    private String description;

    private Long draftVersionId;

    private Long publishedVersionId;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
