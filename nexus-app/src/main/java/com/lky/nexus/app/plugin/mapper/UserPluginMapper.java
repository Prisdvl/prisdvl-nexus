package com.lky.nexus.app.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.plugin.model.entity.UserPluginPO;
import org.apache.ibatis.annotations.Mapper;

/** 插件覆盖行（{@code user_plugin}）。查询一律带 {@code user_id}。 */
@Mapper
public interface UserPluginMapper extends BaseMapper<UserPluginPO> {
}
