package com.lky.nexus.app.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.workflow.model.entity.WorkflowNodeRunPO;
import org.apache.ibatis.annotations.Mapper;

/** 工作流节点轨迹 Mapper。 */
@Mapper
public interface WorkflowNodeRunMapper extends BaseMapper<WorkflowNodeRunPO> {
}
