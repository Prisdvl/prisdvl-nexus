package com.lky.akh.app.auth.model.dto;

/**
 * 登录成功响应。
 *
 * <p>{@code expiresIn} 用 {@code long} 基本类型（秒），保证序列化后仍是数字，
 * 而不是像 {@code Long} 那样变成字符串（ADR-017 的配套约定）。
 *
 * <p>响应里同时回带用户信息，前端登录后无需再发一次请求即可渲染头像/昵称。
 */
public record AuthTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserInfoResponse user) {
}
