package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销当前设备对应的 refresh session。
 *
 * <p>必须带 refreshToken：access JWT 不含 session id，无法定位要撤销的行。
 * 本接口允许在 access 已过期时仍能退出（SecurityConfig 放行）。
 */
public record LogoutRequest(
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}
