package com.lky.nexus.app.auth.model.dto;

/**
 * 两步验证绑定信息（{@code POST /api/users/me/totp/setup} 的响应）。
 *
 * <p>{@code secret} 是 Base32 明文密钥，<b>只在这一刻返回一次</b>：用户要么扫码，
 * 要么手抄进验证器。之后服务端只存它的密文，再也不会回显明文——
 * 所以设置页刷新后拿不到它，这是刻意的。
 *
 * <p>{@code otpauthUri} 是验证器扫码用的 {@code otpauth://} 地址；前端不依赖二维码库，
 * 可以先展示密钥 + 手输步骤，再由调用方决定是否换成图形码。
 */
public record TotpSetupResponse(
        String secret,
        String otpauthUri,
        String issuer,
        String account,
        int digits,
        int periodSeconds) {
}
