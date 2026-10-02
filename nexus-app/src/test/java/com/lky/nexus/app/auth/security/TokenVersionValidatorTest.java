package com.lky.nexus.app.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 令牌版本校验的单元测试。
 *
 * <p>这个类的价值集中在**边界**上：正常路径（版本相等）只要一行，
 * 真正容易写错、且写错了不会立刻暴露的是"没有 ver 声明的旧令牌怎么办"
 * ——按 0 处理才能让本期上线不产生一次全员强制重新登录。
 */
class TokenVersionValidatorTest {

    private final UserAuthCache userAuthCache = mock(UserAuthCache.class);
    private final TokenVersionValidator validator = new TokenVersionValidator(userAuthCache);

    private static Jwt token(String subject, Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("stub-token")
                .header("alg", "HS256")
                .subject(subject);
        extraClaims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    @DisplayName("版本一致：放行")
    void matchingVersionPasses() {
        when(userAuthCache.expectedTokenVersion(100L)).thenReturn(3L);

        assertThat(validator.validate(token("100", Map.of("ver", 3L))).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("令牌不带 ver（5.6 之前签发的）且当前版本为 0：仍然放行，不造成全员重新登录")
    void legacyTokenWithoutVersionPassesOnFreshAccount() {
        when(userAuthCache.expectedTokenVersion(100L)).thenReturn(0L);

        assertThat(validator.validate(token("100", Map.of())).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("令牌不带 ver 但当前版本已 >0（改过密码）：拒绝——旧令牌不能靠缺声明蒙混过关")
    void legacyTokenWithoutVersionRejectedAfterPasswordChange() {
        when(userAuthCache.expectedTokenVersion(100L)).thenReturn(1L);

        assertThat(validator.validate(token("100", Map.of())).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("版本不匹配：拒绝，且错误描述里能看出是「改密码导致」")
    void staleVersionRejected() {
        when(userAuthCache.expectedTokenVersion(100L)).thenReturn(2L);

        var result = validator.validate(token("100", Map.of("ver", 1L)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getDescription()).contains("改密码"));
    }

    @Test
    @DisplayName("ver 是字符串（某些序列化路径）也按数字比对，不因类型差异误杀")
    void versionAsStringIsParsed() {
        when(userAuthCache.expectedTokenVersion(100L)).thenReturn(7L);

        assertThat(validator.validate(token("100", Map.of("ver", "7"))).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("用户不存在（软删除）：拒绝——顺带堵上「删号后令牌还能用」的旧缺口")
    void missingUserRejected() {
        when(userAuthCache.expectedTokenVersion(100L)).thenReturn(null);

        assertThat(validator.validate(token("100", Map.of("ver", 0L))).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("sub 不是合法用户 ID：拒绝，且不白查一次缓存")
    void nonNumericSubjectRejectedWithoutTouchingCache() {
        assertThat(validator.validate(token("not-a-number", Map.of("ver", 0L))).hasErrors()).isTrue();

        verify(userAuthCache, never()).expectedTokenVersion(anyLong());
    }
}
