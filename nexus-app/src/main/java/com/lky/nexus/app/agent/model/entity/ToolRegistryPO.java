package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具注册元数据（{@code ai_tool_registry}，V22）。
 *
 * <p>与 {@link ToolPO} 的分工：那张表是**按用户的覆盖行**（"这个用户关了哪些工具"），
 * 这张表是**每个工具一行**的固有元数据，其中 {@code permission} 是运行时权威配置。
 * 启动时的同步只在行不存在时写入代码声明的默认权限，之后不再覆盖（见 V22 文件头）。
 */
@Data
@TableName("ai_tool_registry")
public class ToolRegistryPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String code;

    private String name;

    private String description;

    /** BUILTIN / MCP */
    private String source;

    /** RETRIEVAL / COMPUTE / NETWORK / OTHER */
    private String category;

    /** READ / WRITE / DANGEROUS：运行时权威 */
    private String permission;

    /** 0 = 全平台禁用（执行前默认拒绝） */
    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
