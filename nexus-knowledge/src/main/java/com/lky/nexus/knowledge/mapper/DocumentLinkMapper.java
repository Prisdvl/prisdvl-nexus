package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.DocumentLinkPO;
import org.apache.ibatis.annotations.Mapper;

/** 文档关联边 Mapper。 */
@Mapper
public interface DocumentLinkMapper extends BaseMapper<DocumentLinkPO> {
}
