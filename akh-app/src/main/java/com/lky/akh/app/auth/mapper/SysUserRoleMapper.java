package com.lky.akh.app.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.akh.app.auth.model.entity.SysUserRolePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色关联表 Mapper。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRolePO> {
}
