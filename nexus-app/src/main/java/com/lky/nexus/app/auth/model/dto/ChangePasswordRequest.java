package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求（5.6）。
 *
 * <p><b>刻意没有 userId 字段</b>：改谁由 JWT 的 {@code sub} 决定
 * （{@code CurrentUser.requireId()}）。允许从请求体传 userId 就是水平越权——
 * 任何人都能把别人的密码改掉（ADR-018）。
 *
 * <p><b>原密码为什么没有长度约束</b>：只对新密码做 8–64 位校验。
 * 如果原密码也套上现行策略，一个"早年注册时策略更宽松、或由脚本初始化的账号"
 * 就会永远改不了自己的密码（旧值不符合新规则 → 请求在校验阶段就被拒，
 * 连"密码不对"都提示不出来）。校验旧密码的方式只有一种：{@code matches}。
 */
public record ChangePasswordRequest(

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "新密码长度需在 8–64 之间")
        String newPassword) {
}
