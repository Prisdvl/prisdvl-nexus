package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 可用的工具（agent_tool 表，V13）——即 §5.3 的"Agent 权限矩阵"的最小形态。
 *
 * <p>语义与 {@code agent_knowledge_base} 保持一致：<b>没有行 = 不限制</b>（装该用户全部启用中的
 * 内置工具），有行 = 白名单。这样"新建的 Agent 默认能用知识检索"不需要任何初始化代码，
 * 也不会出现"忘了绑工具 → Agent 变成哑巴"这种静默降级。
 *
 * <p>关联表是物理行（无逻辑删除），所以 {@code uk_agent_tool} 是安全的。
 *
 * <p>刻意<b>没有</b> {@code enabled} 列（§5.2 设计里有）：白名单的"在不在里面"已经表达了
 * 用不用这个工具；一个恒为 1、且没有任何写入点的标志位只会让后来的人以为它能关
 * （§二十一 的 {@code approval_required} 是另一件事，落 P1）。理由见 V13 的文件头。
 */
@Data
@TableName("agent_tool")
public class AgentToolPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    /** 引用实现的 {@code @Tool(name=...)} 的 code，不是 tool.id（tool 行是覆盖行，可能不存在）。 */
    private String toolCode;

    private LocalDateTime createdAt;
}
