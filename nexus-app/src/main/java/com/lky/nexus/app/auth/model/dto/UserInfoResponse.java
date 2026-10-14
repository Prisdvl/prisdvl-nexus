package com.lky.nexus.app.auth.model.dto;

import java.util.List;

/**
 * 用户信息响应（对外可见的用户视图）。
 *
 * <p><b>刻意不包含 passwordHash</b>：即使加了 {@code @JsonIgnore} 也不如"根本不出现在返回类型里"安全，
 * 这是防止密码哈希泄漏最可靠的做法。
 *
 * <p>{@code id} 是 {@code Long}，会被全局序列化为字符串（ADR-017），前端按字符串处理。
 *
 * <p>{@code emailVerified}：当前绑定邮箱是否已通过归属验证；无邮箱时为 {@code false}。
 */
public record UserInfoResponse(
        Long id,
        String username,
        String nickname,
        String email,
        boolean emailVerified,
        List<String> roles) {
}
