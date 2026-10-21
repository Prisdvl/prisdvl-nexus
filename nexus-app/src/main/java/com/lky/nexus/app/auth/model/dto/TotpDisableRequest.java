package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 关闭两步验证（{@code POST /api/users/me/totp/disable}）。
 *
 * <p><b>必须验密码</b>：关闭第二因子等于把账号安全性降一级，如果只凭已登录的 Token 就能关，
 * 那么"Token 被盗"这件事会立刻退化成"账号被完整接管"。验一次密码，至少让攻击者还需要密码。
 *
 * <p>这里<b>不</b>接受 TOTP 码作为替代凭证：丢手机的人正是要靠密码进来关掉它，
 * 否则用户会被自己开启的功能永久锁在门外。
 */
public record TotpDisableRequest(
        @NotBlank(message = "请输入当前密码")
        @Size(max = 128, message = "密码过长")
        String password) {
}
