package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.ImportTaskPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导入任务 Mapper。
 */
@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTaskPO> {
}
