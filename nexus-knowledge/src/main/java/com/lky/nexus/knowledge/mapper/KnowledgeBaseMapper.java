package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库 Mapper。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBasePO> {
}
