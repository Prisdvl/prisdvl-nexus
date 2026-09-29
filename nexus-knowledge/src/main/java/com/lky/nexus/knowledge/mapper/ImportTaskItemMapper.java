package com.lky.nexus.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导入任务明细 Mapper。
 */
@Mapper
public interface ImportTaskItemMapper extends BaseMapper<ImportTaskItemPO> {
}
