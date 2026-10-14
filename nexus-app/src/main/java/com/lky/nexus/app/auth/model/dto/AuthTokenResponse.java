package com.lky.nexus.app.auth.model.dto;

/**
 * 登录成功响应。
 *
 * <p>{@code expiresIn} 用 {@code long} 基本类型（秒），保证序列化后仍是数字，
 * 而不是像 {@code Long} 那样变成字符串（ADR-017 的配套约定）。
 *
 * <p>响应里同时回带用户信息，前端登录后无需再发一次请求即可渲染头像/昵称。
 *
 * <p>Phase 4 增加 {@code refreshToken}：opaque 长寿命凭证，客户端须安全保存；
 * 服务端只存其 hash。既有字段（accessToken / tokenType / expiresIn / user）保持兼容。
 */
public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserInfoResponse user) {
}
