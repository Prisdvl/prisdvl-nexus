package com.lky.nexus.app.workspace;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区（平台化 Phase 2）。
 *
 * <p>第一阶段只有"个人工作区"（注册时自动创建，type=PERSONAL，1:1 用户）；
 * 团队工作区（type=TEAM + 成员角色表）在 Phase 8 做。字段与架构完全兼容。
 */
@Data
@TableName("workspace")
public class WorkspacePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所有者（第一阶段即唯一成员）。 */
    private Long userId;

    private String name;

    private String description;

    /** PERSONAL / TEAM */
    private String type;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}