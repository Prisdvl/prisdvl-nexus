package com.lky.nexus.app.auth.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体（sys_user）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code @TableId(type = ASSIGN_ID)} —— 雪花 ID，避免自增 ID 暴露业务规模（见 docs/database.md 约定）；</li>
 *   <li>{@code @TableLogic} —— 逻辑删除，删除后 MyBatis-Plus 自动追加 {@code deleted = 0} 条件；</li>
 *   <li>时间字段不设自动填充：第一版由数据库默认值与业务代码显式赋值，避免引入 {@code MetaObjectHandler} 造成隐式行为。</li>
 * </ul>
 */
@Data
@TableName("sys_user")
public class SysUserPO {

    /** 主键：雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名，全局唯一 */
    private String username;

    /** 邮箱，可空 */
    private String email;

    /** BCrypt 密码哈希，绝不存明文 */
    private String passwordHash;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatarUrl;

    /** 状态：1 正常，0 禁用 */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除标记：0 未删除，1 已删除 */
    @TableLogic
    private Integer deleted;
}
