package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档标签 Mapper。
 */
@Mapper
public interface DocumentTagMapper extends BaseMapper<DocumentTagPO> {
}
