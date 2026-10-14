package com.lky.nexus.app.auth.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 用 refresh token 换取新的 access + refresh（rotation）。
 */
public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}
