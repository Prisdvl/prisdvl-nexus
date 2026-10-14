package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 请求密码重置验证码（公开接口）。
 *
 * <p>刻意只有 {@code email}：不接受 userId / 用户名，避免客户端指定重置目标。
 */
public record PasswordResetRequest(

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱长度不能超过 128")
        String email) {
}
