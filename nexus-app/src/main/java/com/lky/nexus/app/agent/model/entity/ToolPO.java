package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Tool 的用户态（tool 表，V13）。
 *
 * <p><b>这张表存的是"覆盖"，不是"定义"</b>：工具的定义（名字、给模型看的描述、入参 schema）
 * 全部来自代码里的 {@code @Tool} 方法，由 {@code ToolCatalog} 反射出来；
 * 这里只存用户拥有的那一个字段——{@code enabled}。
 * <b>没有行 = 用代码侧的默认（启用）</b>，所以新用户、清库之后工具都不会凭空消失。
 *
 * <p>为什么这么设计、为什么描述性字段一概不落库（避免死数据），见 V13 迁移脚本的文件头注释
 * 与 decisions.md B.38。
 *
 * <p>{@code ownerId} 与 {@code workspaceId} 并存：建表时就带上归属维度（§4.3 的顺序），
 * 但按 Phase 2 的刻意安排，<b>查询仍走 owner_id</b>，等 Phase 8 有成员模型时再切到 workspace。
 */
@Data
@TableName("tool")
public class ToolPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属工作区（个人工作区，1:1 用户）。 */
    private Long workspaceId;

    /** 所有者：所有查询必须带这个条件。 */
    private Long ownerId;

    /** 工具标识，来自实现的 {@code @Tool(name=...)}；也是模型看到的名字。 */
    private String code;

    /** 0 = 该用户停用此工具（今天就生效的拦截点）。 */
    private Boolean enabled;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
