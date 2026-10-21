package com.lky.nexus.app.auth.model.dto;

import java.util.List;

/**
 * 启用两步验证成功后的恢复码（{@code POST /api/users/me/totp/enable} 的响应）。
 *
 * <p>这是明文恢复码<b>唯一</b>一次出现的地方：服务端只存 SHA-256。前端必须提示
 * "现在记下来，之后不再显示"，而不是给个可以随时回看的列表。
 */
public record TotpEnableResponse(List<String> recoveryCodes) {
}
