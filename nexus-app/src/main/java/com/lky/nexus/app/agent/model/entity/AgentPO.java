package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 表实体（agent）。
 *
 * <p>字段类型说明沿用 {@code ChatConversationPO} 的约定：{@code status} 存字符串，
 * 枚举转换留在服务层，持久化层不做隐式魔法。
 *
 * <p>{@code ownerId} 与 {@code workspaceId} 并存：建表时就带上归属维度（§4.3 的顺序），
 * 但按 Phase 2 的刻意安排，<b>查询仍走 owner_id</b>；等 Phase 8 有成员模型时再把权限维度
 * 切到 workspace，那时不需要回头补数据。
 */
@Data
@TableName("agent")
public class AgentPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属工作区（个人工作区，1:1 用户）。 */
    private Long workspaceId;

    /** 所有者：所有查询必须带这个条件。 */
    private Long ownerId;

    /** 稳定标识（小写字母/数字/._-），供 tool/workflow/application 引用。 */
    private String code;

    private String name;

    private String description;

    /** 系统提示；为空时由服务层补成内置默认提示。 */
    private String systemPrompt;

    /** 模型逻辑角色（ai.roles 的 key）；为空时由服务层补成 chat.agent。 */
    private String modelRole;

    /** 回答策略（4.4）：KB_PREFERRED / KB_ONLY；为空时由服务层补成默认。 */
    private String answerPolicy;

    /**
     * 采样温度（5.4）：0.00–2.00；<b>null = 跟随全局 {@code ai.defaults.temperature}</b>。
     *
     * <p>null 与 0 不同义（0 = 显式要求确定性），理由见 V16 的文件头。范围校验在服务层，
     * 这里不设默认值——任何"补个 0.7 兜底"的写法都会让"跟随全局"这条路在内存里先断掉。
     */
    private Double temperature;

    /** DRAFT / PUBLISHED / DISABLED。 */
    private String status;

    /** 1 = 系统内置默认 Agent（不可删除、不可停用）。 */
    private Boolean builtin;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
