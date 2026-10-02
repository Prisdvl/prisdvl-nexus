package com.lky.nexus.app.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 令牌版本校验：让改密码能**立刻**作废该用户此前签发的全部访问令牌。
 *
 * <p>挂在 {@code SecurityConfig.jwtDecoder()} 上，随 JWT 解码一起跑，
 * 因此不需要新写一个过滤器，也不需要在业务代码里埋任何检查。
 *
 * <p>规则：令牌里的 {@code ver} 声明 == 该用户当前的 {@code sys_user.token_version}。
 * <ul>
 *   <li><b>没有 {@code ver} 声明按 0 处理</b>——本期之前签发的令牌因此仍然有效，
 *       不会造成一次"全员被迫重新登录"。存量行的 {@code token_version} 默认也是 0。</li>
 *   <li>版本不等 = 该令牌是在改密码之前签发的 → 拒绝。</li>
 *   <li>用户不存在（软删除）→ 拒绝。这顺带堵上了"删号之后令牌还能用"的旧缺口。</li>
 * </ul>
 *
 * <p>失败以 {@code JwtValidationException}（{@code AuthenticationException} 子类）冒到
 * {@code RestAuthenticationEntryPoint} → 401 + {@code UNAUTHENTICATED(2001)}，
 * 前端既有拦截器会清 token 跳登录页——正是"改完密码回登录页"的期望交互。
 * 错误信息只进日志，不进响应体（入口对所有认证异常都是一句话，不泄露细节）。
 */
@Component
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionValidator.class);

    /** 令牌里承载版本的声明名。 */
    static final String CLAIM_VERSION = "ver";

    private final UserAuthCache userAuthCache;

    public TokenVersionValidator(UserAuthCache userAuthCache) {
        this.userAuthCache = userAuthCache;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Long userId = parseSubject(jwt);
        if (userId == null) {
            return failure("令牌的 sub 不是合法的用户标识");
        }

        Long expected = userAuthCache.expectedTokenVersion(userId);
        if (expected == null) {
            return failure("用户不存在或已注销");
        }

        long presented = versionOf(jwt);
        if (presented != expected) {
            log.warn("令牌版本不匹配，拒绝该令牌 userId={}, 令牌版本={}, 当前版本={}",
                    userId, presented, expected);
            return failure("令牌已失效（改密码后需要重新登录）");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private Long parseSubject(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 读 {@code ver} 声明；缺失或类型异常一律按 0（= 首次签发时的版本）。 */
    private long versionOf(Jwt jwt) {
        Object raw = jwt.getClaim(CLAIM_VERSION);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
        return 0L;
    }

    private OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}
