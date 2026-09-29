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
        String password) {
}
