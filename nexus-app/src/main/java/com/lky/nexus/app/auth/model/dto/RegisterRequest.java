package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>校验规则即接口契约（由 Spring Validation 自动执行，失败统一返回 1001）：
 * 用户名 3–32 位字母/数字/下划线/连字符；密码 8–64 位；邮箱可选。
 *
 * <p>关于用户名允许连字符：最初只放开下划线，结果两次在测试/联调时被"conv-lifecycle"
 * 这类名字绊住（注册静默 400，后续登录表现为 401，排查成本高）。主流产品（GitHub、
 * GitLab）都允许连字符，因此放宽。**校验规则要按"真实用户会怎么起名"来定，
 * 而不是按实现方便程度来定。**
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在 3–32 之间")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "用户名只能包含字母、数字、下划线与连字符")
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
