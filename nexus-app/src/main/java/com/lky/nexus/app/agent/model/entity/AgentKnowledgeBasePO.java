package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 绑定的知识库（agent_knowledge_base）。
 *
 * <p>物理关联表：没有逻辑删除，改绑 = 删旧行 + 插新行（见 {@code AgentService.replaceKnowledgeBases}）。
 * 好处是 {@code UNIQUE(agent_id, kb_id)} 这种唯一键在这里是安全的——
 * 逻辑删除的表上用唯一键，删两次同名资源就会撞键（{@code agent.code} 因此刻意没有唯一键）。
 */
@Data
@TableName("agent_knowledge_base")
public class AgentKnowledgeBasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    private Long kbId;

    private LocalDateTime createdAt;
}
