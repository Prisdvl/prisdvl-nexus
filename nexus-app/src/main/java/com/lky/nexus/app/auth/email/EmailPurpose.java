package com.lky.nexus.app.auth.email;

/**
 * 邮箱验证码用途。写入 Redis key 的一段，必须稳定、可枚举。
 *
 * <p>不同用途的验证码严格隔离，不可互用。
 */
public enum EmailPurpose {

    /** 注册时校验邮箱归属（后续阶段，本轮不接入）。 */
    REGISTER,

    /** 忘记密码重置（由 PasswordResetService 接入）。 */
    RESET_PASSWORD,

    /** 已登录用户验证当前绑定邮箱归属。 */
    VERIFY_EMAIL;

    /** 用作 Redis key 片段（大写、稳定）。 */
    public String keyToken() {
        return name();
    }
}
