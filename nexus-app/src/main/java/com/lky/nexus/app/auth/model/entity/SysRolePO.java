package com.lky.nexus.app.auth.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色表实体（sys_role）。RBAC 的角色字典，由初始化脚本预置，不在运行期动态创建。
 */
@Data
@TableName("sys_role")
public class SysRolePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色码，如 USER / ADMIN；已被写入 JWT 的 roles 声明，属于对外契约，不要随意改名。 */
    private String code;

    /** 角色名称，供后台展示。 */
    private String name;

    private String description;
}
