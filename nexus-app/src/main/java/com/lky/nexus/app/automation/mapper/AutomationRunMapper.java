package com.lky.nexus.app.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.automation.model.entity.AutomationRunPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自动化运行记录。只做基础 CRUD 与排序查询，复杂过滤放服务层用 LambdaQuery 拼。
 */
@Mapper
public interface AutomationRunMapper extends BaseMapper<AutomationRunPO> {
}
