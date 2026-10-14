package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交邮箱验证码（已登录验邮）。
 *
 * <p>刻意<b>没有</b> userId / email 字段：用户身份只从 JWT 取，目标邮箱只读数据库绑定值。
 */
public record VerifyEmailRequest(

        @NotBlank(message = "验证码不能为空")
        @Size(min = 4, max = 10, message = "验证码长度不正确")
        String code) {
}
