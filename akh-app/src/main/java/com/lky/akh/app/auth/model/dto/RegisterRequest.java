package com.lky.akh.app.auth.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>校验规则即接口契约（由 Spring Validation 自动执行，失败统一返回 1001）：
 * 用户名 3–32 位字母/数字/下划线；密码 8–64 位；邮箱可选。
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在 3–32 之间")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字与下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在 8–64 之间")
        String password,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱长度不能超过 128")
        String email,

        @Size(max = 64, message = "昵称长度不能超过 64")
        String nickname) {
}
