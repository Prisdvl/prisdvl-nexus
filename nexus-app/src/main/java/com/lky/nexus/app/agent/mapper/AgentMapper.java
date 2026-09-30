package com.lky.nexus.app.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.agent.model.entity.AgentPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 表 Mapper。
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentPO> {
}
