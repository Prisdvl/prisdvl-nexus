package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>注意：登录接口<b>不</b>对密码做长度/复杂度校验——那是注册时的规则。
 * 登录时只需要"非空"，否则老用户改了密码规则后就登不进来了。
 */
public record LoginRequest(

        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password,

        /**
         * 两步验证码（TOTP 6 位数字，或一枚一次性恢复码）。
         *
         * <p>刻意<b>不</b>加 {@code @NotBlank}：绝大多数账号没开启两步验证，
         * 强制必填会让登录接口对所有人变成两段式。是否必填由服务端按账号状态决定
         * （需要而未提供 → 2011 {@code TOTP_REQUIRED}）。
         *
         * <p>也不能限定成 {@code \d{6}}：恢复码是字母数字混合，登录是它唯一的入口，
         * 用 TOTP 的格式把它挡掉等于把恢复码变成废纸。
         */
        String totpCode) {
}
