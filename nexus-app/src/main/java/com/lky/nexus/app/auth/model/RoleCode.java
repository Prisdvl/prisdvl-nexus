package com.lky.nexus.app.auth.model;

/**
 * 角色码常量。
 *
 * <p>与 {@code sys_role.code} 一一对应；不要散落字符串字面量，
 * 否则改角色名时会出现"数据库改了、代码没改"的静默权限问题。
 */
public final class RoleCode {

    /** 普通用户：默认角色，注册时自动授予。 */
    public static final String USER = "USER";

    /** 管理员：可访问 {@code /api/admin/**}。 */
    public static final String ADMIN = "ADMIN";

    private RoleCode() {
    }
}
