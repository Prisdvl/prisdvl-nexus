package com.lky.nexus.app.auth;

import com.lky.nexus.app.auth.email.EmailCodeService;
import com.lky.nexus.app.auth.email.EmailPurpose;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.security.UserAuthCache;
import com.lky.nexus.app.auth.service.PasswordResetService;
import com.lky.nexus.app.auth.session.RefreshTokenService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码重置服务单元测试（手写桩 / JDK Proxy，避免本机 JDK 上 Mockito inline agent 不可用）。
 */
class PasswordResetServiceTest {

    private final AtomicReference<SysUserPO> verified = new AtomicReference<>();
    private final AtomicInteger updateRows = new AtomicInteger(1);
    private final AtomicInteger updateCalls = new AtomicInteger();
    private final AtomicReference<RuntimeException> updateError = new AtomicReference<>();
    private final AtomicInteger sendCalls = new AtomicInteger();
    private final AtomicInteger ipCheckCalls = new AtomicInteger();
    private final AtomicInteger verifyCalls = new AtomicInteger();
    private final AtomicReference<BusinessException> sendError = new AtomicReference<>();
    private final AtomicReference<BusinessException> ipCheckError = new AtomicReference<>();
    private final AtomicReference<String> lastSendEmail = new AtomicReference<>();
    private final AtomicReference<EmailPurpose> lastPurpose = new AtomicReference<>();
    private final AtomicReference<String> lastIp = new AtomicReference<>();
    private final AtomicReference<String> lastEncoded = new AtomicReference<>();
    private final List<Long> invalidated = new ArrayList<>();
    private final List<Long> revokedSessions = new ArrayList<>();

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        verified.set(null);
        updateRows.set(1);
        updateCalls.set(0);
        updateError.set(null);
        sendCalls.set(0);
        ipCheckCalls.set(0);
        verifyCalls.set(0);
        sendError.set(null);
        ipCheckError.set(null);
        invalidated.clear();
        revokedSessions.clear();

        SysUserMapper users = (SysUserMapper) Proxy.newProxyInstance(
                SysUserMapper.class.getClassLoader(),
                new Class<?>[]{SysUserMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectOne".equals(name)) {
                        return verified.get();
                    }
                    if ("update".equals(name)) {
                        updateCalls.incrementAndGet();
                        RuntimeException err = updateError.get();
                        if (err != null) {
                            throw err;
                        }
                        return updateRows.get();
                    }
                    if ("toString".equals(name)) {
                        return "FakeSysUserMapper";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(name);
                });

        EmailCodeService codes = new EmailCodeService(null, null, null, null) {
            @Override
            public void checkIpSendLimit(String clientIp) {
                ipCheckCalls.incrementAndGet();
                lastIp.set(clientIp);
                BusinessException err = ipCheckError.get();
                if (err != null) {
                    throw err;
                }
            }

            @Override
            public void sendSkippingIpLimit(String email, EmailPurpose purpose) {
                sendCalls.incrementAndGet();
                lastSendEmail.set(email);
                lastPurpose.set(purpose);
                BusinessException err = sendError.get();
                if (err != null) {
                    throw err;
                }
            }

            @Override
            public void send(String email, EmailPurpose purpose, String clientIp) {
                checkIpSendLimit(clientIp);
                sendSkippingIpLimit(email, purpose);
            }

            @Override
            public void verify(String email, EmailPurpose purpose, String code) {
                verifyCalls.incrementAndGet();
            }
        };

        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                lastEncoded.set(rawPassword.toString());
                return "hash:" + rawPassword;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };

        UserAuthCache cache = new UserAuthCache(null, null, null, null) {
            @Override
            public void invalidate(Long userId) {
                invalidated.add(userId);
            }
        };

        RefreshTokenService refresh = new RefreshTokenService(null, null, null, null, null, null) {
            @Override
            public void revokeAllSessions(Long userId) {
                revokedSessions.add(userId);
            }
        };

        service = new PasswordResetService(users, codes, encoder, cache, refresh);
    }

    @Test
    @DisplayName("未注册 / 未验证邮箱：仍做 IP 限流、不发信、不抛错（防枚举）")
    void requestDoesNotSendWhenNoVerifiedUser() {
        service.requestReset("nobody@example.com", "127.0.0.1");
        assertThat(ipCheckCalls.get()).isEqualTo(1);
        assertThat(lastIp.get()).isEqualTo("127.0.0.1");
        assertThat(sendCalls.get()).isZero();
    }

    @Test
    @DisplayName("已验证邮箱：发送 RESET_PASSWORD 验证码")
    void requestSendsForVerifiedEmail() {
        verified.set(user(1L, "a@b.com"));
        service.requestReset("A@B.com", "10.0.0.1");
        assertThat(ipCheckCalls.get()).isEqualTo(1);
        assertThat(sendCalls.get()).isEqualTo(1);
        assertThat(lastPurpose.get()).isEqualTo(EmailPurpose.RESET_PASSWORD);
        assertThat(lastSendEmail.get()).isEqualTo("a@b.com");
        assertThat(lastIp.get()).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("发信 INTERNAL_ERROR：吞掉并记日志，不向上抛（防枚举）")
    void sendFailureDoesNotPropagate() {
        verified.set(user(1L, "a@b.com"));
        sendError.set(new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试"));
        service.requestReset("a@b.com", "127.0.0.1");
        assertThat(sendCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("邮箱冷却：吞掉 RATE_LIMITED，不向上抛（防枚举）")
    void emailCooldownDoesNotPropagate() {
        verified.set(user(1L, "a@b.com"));
        sendError.set(new BusinessException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁，请 60 秒 后再试"));
        service.requestReset("a@b.com", "127.0.0.1");
        assertThat(ipCheckCalls.get()).isEqualTo(1);
        assertThat(sendCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("IP 限流：在查库前向上抛 RATE_LIMITED")
    void ipRateLimitedPropagatesBeforeLookup() {
        verified.set(user(1L, "a@b.com"));
        ipCheckError.set(new BusinessException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁（每小时上限 2 次），请稍后再试"));
        assertThatThrownBy(() -> service.requestReset("a@b.com", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
        assertThat(sendCalls.get()).isZero();
    }

    @Test
    @DisplayName("IP 限流：未注册邮箱也走同一规则（查库前抛出，不发信）")
    void ipRateLimitedAppliesToUnknownEmail() {
        verified.set(null);
        ipCheckError.set(new BusinessException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁（每小时上限 2 次），请稍后再试"));
        assertThatThrownBy(() -> service.requestReset("nobody@example.com", "203.0.113.9"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
        assertThat(ipCheckCalls.get()).isEqualTo(1);
        assertThat(lastIp.get()).isEqualTo("203.0.113.9");
        assertThat(sendCalls.get()).isZero();
    }

    @Test
    @DisplayName("落库抛异常：不返回成功；验证码已消费")
    void dbUpdateFailureDoesNotClaimSuccess() {
        verified.set(user(9L, "a@b.com"));
        updateError.set(new RuntimeException("db down"));
        assertThatThrownBy(() -> service.confirmReset("a@b.com", "123456", "NewPassw0rd"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
                    assertThat(be.getMessage()).contains("重新获取");
                });
        assertThat(verifyCalls.get()).isEqualTo(1);
        assertThat(invalidated).isEmpty();
    }

    @Test
    @DisplayName("落库 rows≠1：PARAM_INVALID，不宣称成功、不失效缓存")
    void zeroRowsDoesNotClaimSuccess() {
        verified.set(user(9L, "a@b.com"));
        updateRows.set(0);
        assertThatThrownBy(() -> service.confirmReset("a@b.com", "123456", "NewPassw0rd"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(invalidated).isEmpty();
    }

    @Test
    @DisplayName("验证码消费后账户已不再符合条件：PARAM_INVALID")
    void noVerifiedUserAfterConsumeFails() {
        verified.set(null);
        assertThatThrownBy(() -> service.confirmReset("a@b.com", "123456", "NewPassw0rd"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThat(verifyCalls.get()).isEqualTo(1);
        assertThat(updateCalls.get()).isZero();
        assertThat(invalidated).isEmpty();
    }

    @Test
    @DisplayName("成功重置：更新密码、递增版本、失效缓存、撤销全部 refresh 会话")
    void confirmSuccessInvalidatesCache() {
        verified.set(user(9L, "a@b.com"));
        updateRows.set(1);
        service.confirmReset("a@b.com", "123456", "NewPassw0rd");
        assertThat(verifyCalls.get()).isEqualTo(1);
        assertThat(updateCalls.get()).isEqualTo(1);
        assertThat(lastEncoded.get()).isEqualTo("NewPassw0rd");
        assertThat(invalidated).containsExactly(9L);
        assertThat(revokedSessions).containsExactly(9L);
    }

    private static SysUserPO user(Long id, String email) {
        SysUserPO u = new SysUserPO();
        u.setId(id);
        u.setEmail(email);
        u.setEmailVerified(1);
        return u;
    }
}
