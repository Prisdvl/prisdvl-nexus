package com.lky.prisma.app.auth.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-角色关联表实体（sys_user_role）。
 *
 * <p>设计为多对多关联表（而不是在用户表上放一个 role 字段）：
 * 一个用户可同时是 USER 与 ADMIN；未来加"知识库协作者"等角色时也不用改表结构。
 */
@Data
@TableName("sys_user_role")
public class SysUserRolePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long roleId;
}
