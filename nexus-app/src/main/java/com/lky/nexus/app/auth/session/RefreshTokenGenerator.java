package com.lky.nexus.app.auth.session;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 生成高熵 opaque refresh token（非 JWT、不编码用户信息）。
 *
 * <p>32 字节 SecureRandom → Base64URL（无填充），约 43 字符。
 */
public final class RefreshTokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 32;

    private RefreshTokenGenerator() {
    }

    public static String generate() {
        byte[] buf = new byte[BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
