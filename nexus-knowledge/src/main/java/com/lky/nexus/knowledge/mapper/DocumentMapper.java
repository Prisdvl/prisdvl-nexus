package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档 Mapper。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentPO> {
}
