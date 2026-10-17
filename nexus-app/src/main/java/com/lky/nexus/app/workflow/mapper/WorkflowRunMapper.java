package com.lky.nexus.app.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.workflow.model.entity.WorkflowRunPO;
import org.apache.ibatis.annotations.Mapper;

/** 工作流运行 Mapper。 */
@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunPO> {
}
