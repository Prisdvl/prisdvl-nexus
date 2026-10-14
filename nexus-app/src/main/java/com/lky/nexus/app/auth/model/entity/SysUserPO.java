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

    /**
     * 当前 {@link #email} 是否已通过归属验证（迁移 V20）。
     *
     * <p>1 = 已验证；0 = 未验证（含空邮箱、刚绑定、刚更换、存量历史邮箱）。
     * 更换邮箱地址后必须重新变为 0；仅改昵称等不得清除。
     */
    private Integer emailVerified;

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

    /**
     * 令牌版本（5.6，迁移 V18）：改密码时 {@code +1}，使该用户此前签发的全部访问令牌立即失效。
     *
     * <p>校验发生在 {@code TokenVersionValidator}——它拿令牌里的 {@code ver} 声明与本列比对，
     * 所以业务代码一般不直接读这个字段（唯一的例外是签发令牌时要把它写进声明）。
     * 注册时不赋值，落数据库默认值 0。
     */
    private Long tokenVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除标记：0 未删除，1 已删除 */
    @TableLogic
    private Integer deleted;
}
