package com.lky.nexus.app.auth.model.dto;

import java.time.LocalDateTime;

/**
 * 两步验证状态（{@code GET /api/users/me/totp} 的响应）。
 *
 * <p>{@code enabled = false} 时其余字段没有意义，前端只展示"未开启 + 绑定按钮"。
 *
 * @param enabled           登录是否已要求第二因子
 * @param recoveryCodesLeft 还没用掉的恢复码数量
 * @param confirmedAt       绑定通过的时间（未绑定为 null）
 */
public record TotpStatusResponse(boolean enabled, long recoveryCodesLeft, LocalDateTime confirmedAt) {
}
