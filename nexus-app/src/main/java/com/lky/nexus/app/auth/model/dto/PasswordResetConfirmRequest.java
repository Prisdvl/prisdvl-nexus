package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交验证码并重置密码（公开接口）。
 *
 * <p>刻意没有 userId / 用户名：目标账户只按规范化邮箱在库中查找，且须仍为已验证状态。
 */
public record PasswordResetConfirmRequest(

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱长度不能超过 128")
        String email,

        @NotBlank(message = "验证码不能为空")
        @Size(min = 4, max = 10, message = "验证码长度不正确")
        String code,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "新密码长度需在 8–64 之间")
        String newPassword) {
}
