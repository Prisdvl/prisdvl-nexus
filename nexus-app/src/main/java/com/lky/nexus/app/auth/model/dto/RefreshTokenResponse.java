package com.lky.nexus.app.auth.model.dto;

/**
 * refresh 成功响应（不含 user，与登录响应字段兼容子集）。
 */
public record RefreshTokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {
}
