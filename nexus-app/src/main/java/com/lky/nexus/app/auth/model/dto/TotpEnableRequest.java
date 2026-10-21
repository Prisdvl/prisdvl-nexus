package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 启用两步验证（{@code POST /api/users/me/totp/enable}）。
 *
 * <p>必须带上验证器当前显示的 6 位码：没有它就无法证明"用户真的把密钥装进了自己的验证器"，
 * 而这个证明正是防呆的全部意义——否则一次误点就能把账号锁死。
 */
public record TotpEnableRequest(
        @NotBlank(message = "请输入验证器中的验证码")
        @Pattern(regexp = "\\d{6}", message = "验证码应为 6 位数字")
        String code) {
}
