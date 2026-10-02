package com.lky.nexus.app.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 限流器的单元测试。
 */
class RateLimiterTest {

    private ObservabilityRegistry observability;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        NexusSecurityProperties properties = new NexusSecurityProperties();
        properties.getRateLimit().setPerMinute(3);
        properties.getRegister().setPerHourPerIp(2);

        observability = mock(ObservabilityRegistry.class);
        limiter = new RateLimiter(new InMemorySecurityStore(), properties, observability);
    }

    @Test
    @DisplayName("上限内放行，第 limit+1 次报 7001")
    void exceedingPerMinuteLimitRejected() {
        limiter.checkUser(1L);
        limiter.checkUser(1L);
        limiter.checkUser(1L);

        assertThatThrownBy(() -> limiter.checkUser(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    @Test
    @DisplayName("额度按用户隔离：A 打满不影响 B")
    void quotaIsPerUser() {
        limiter.checkUser(1L);
        limiter.checkUser(1L);
        limiter.checkUser(1L);
        assertThatThrownBy(() -> limiter.checkUser(1L)).isInstanceOf(BusinessException.class);

        assertThatCode(() -> limiter.checkUser(2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("注册入口按 IP 限流，且与用户限流互不干扰（用的是不同的键前缀）")
    void registerLimitIsPerIpAndIndependent() {
        limiter.checkRegister("1.2.3.4");
        limiter.checkRegister("1.2.3.4");

        assertThatThrownBy(() -> limiter.checkRegister("1.2.3.4")).isInstanceOf(BusinessException.class);
        // 另一个 IP 有自己的桶
        assertThatCode(() -> limiter.checkRegister("5.6.7.8")).doesNotThrowAnyException();
        // 同一台机器上的已登录用户也不受影响（键不同）
        assertThatCode(() -> limiter.checkUser(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 存储不可用时 fail-open：放行并计数（限流是防滥用，不是业务正确性）")
    void storeFailureFailsOpenAndCounts() {
        SecurityStore broken = mock(SecurityStore.class);
        // ⚠️ RateLimiter 走的是 increment（接口上的 default 方法）。
        // Mockito 不会替 mock 执行 default 实现，所以必须 stub increment 本身——
        // 只 stub add 的话，mock 会直接返回 0，测试会因为"根本没抛异常"而假通过。
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(broken).increment(anyString(), any(Duration.class));

        NexusSecurityProperties properties = new NexusSecurityProperties();
        properties.getRateLimit().setPerMinute(1);
        RateLimiter degraded = new RateLimiter(broken, properties, observability);

        // 远超上限，但每次都放行
        for (int i = 0; i < 10; i++) {
            assertThatCode(() -> degraded.checkUser(1L)).doesNotThrowAnyException();
        }

        verify(observability, org.mockito.Mockito.atLeastOnce())
                .increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
    }
}
