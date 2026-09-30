package com.lky.nexus.app.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.agent.model.entity.AgentKnowledgeBasePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent↔知识库 关联表 Mapper。
 */
@Mapper
public interface AgentKnowledgeBaseMapper extends BaseMapper<AgentKnowledgeBasePO> {
}
