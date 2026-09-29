package com.lky.akh.app.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.akh.app.auth.model.entity.SysRolePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色表 Mapper。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRolePO> {

    /**
     * 查询某用户拥有的角色码列表。
     *
     * <p>这里用显式 SQL 而不是 MyBatis-Plus 的条件构造器：跨表关联查询写成 SQL 更直观，
     * 也便于后续用 EXPLAIN 优化；单表 CRUD 才用 BaseMapper。
     *
     * @param userId 用户 ID
     * @return 角色码，例如 {@code ["ADMIN", "USER"]}；无角色时返回空列表
     */
    @Select("""
            SELECT r.code
            FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY r.code
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
