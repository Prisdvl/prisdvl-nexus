package com.lky.nexus.app.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.workflow.model.entity.WorkflowPO;
import org.apache.ibatis.annotations.Mapper;

/** 工作流主表 Mapper。 */
@Mapper
public interface WorkflowMapper extends BaseMapper<WorkflowPO> {
}
