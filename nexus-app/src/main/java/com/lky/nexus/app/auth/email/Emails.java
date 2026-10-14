package com.lky.nexus.app.auth.email;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 邮箱规范化：trim + {@link Locale#ROOT} 小写。空串非法。
 *
 * <p>格式检查刻意宽松（与 Jakarta {@code @Email} 同级），只拦明显非法串；
 * 写入路径另有 Bean Validation，验邮发送路径再做一道防脏数据。
 */
public final class Emails {

    /** 本地部分@域名.顶级域；不追求 RFC 完备。 */
    private static final Pattern LOOSE_EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private Emails() {
    }

    public static String normalize(String email) {
        if (email == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "邮箱不能为空");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "邮箱不能为空");
        }
        return normalized;
    }

    /**
     * 规范化并校验基本格式（验邮发送 / 校验入口用）。
     */
    public static String normalizeValid(String email) {
        String normalized = normalize(email);
        if (!LOOSE_EMAIL.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "邮箱格式不正确");
        }
        return normalized;
    }

    /**
     * 资料更新用：null / 空白 → null（清空语义）；否则同 {@link #normalize}。
     */
    public static String normalizeOrNull(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
