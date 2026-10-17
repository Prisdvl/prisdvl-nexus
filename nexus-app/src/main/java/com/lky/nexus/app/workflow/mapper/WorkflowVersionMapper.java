package com.lky.nexus.app.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.workflow.model.entity.WorkflowVersionPO;
import org.apache.ibatis.annotations.Mapper;

/** 工作流版本 Mapper。 */
@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersionPO> {
}
