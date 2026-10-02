package com.lky.nexus.app.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 登录失败限制的单元测试（用真实的内存 store，不 mock 存储语义）。
 *
 * <p>这里最要紧的是最后两条：<b>fail-open</b> 与<b>"成功即清零"</b>。
 * 前者写错会让 Redis 抖动变成全站登不进来；后者写错会让"错 4 次、成功 1 次"
 * 循环着的正常用户在第 5 轮被自己的手误锁住。
 */
class LoginAttemptLimiterTest {

    private SecurityStore store;
    private ObservabilityRegistry observability;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        NexusSecurityProperties properties = new NexusSecurityProperties();
        properties.getLogin().setMaxFailures(3);
        properties.getLogin().setWindow(Duration.ofMinutes(15));
        properties.getLogin().setLockDuration(Duration.ofMinutes(15));

        store = new InMemorySecurityStore();
        observability = mock(ObservabilityRegistry.class);
        limiter = new LoginAttemptLimiter(store, properties, observability);
    }

    @Test
    @DisplayName("未达阈值：一直放行，失败多少次都不锁（阈值是 3）")
    void belowThresholdStaysOpen() {
        limiter.recordFailure("lky");
        limiter.recordFailure("lky");

        assertThatCode(() -> limiter.checkAllowed("lky")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("达到阈值：冻结，报 7001（429），且信息里给出等待分钟数")
    void reachingThresholdLocksAccount() {
        limiter.recordFailure("lky");
        limiter.recordFailure("lky");
        limiter.recordFailure("lky");

        assertThatThrownBy(() -> limiter.checkAllowed("lky"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("15 分钟")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    @Test
    @DisplayName("冻结与用户名绑定：锁了 A 不影响 B")
    void lockIsPerUsername() {
        limiter.recordFailure("locked-user");
        limiter.recordFailure("locked-user");
        limiter.recordFailure("locked-user");

        assertThatThrownBy(() -> limiter.checkAllowed("locked-user")).isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiter.checkAllowed("other-user")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 登录成功即清零：否则正常用户几次手误之后会被自己锁住")
    void successfulLoginResetsCounter() {
        limiter.recordFailure("lky");
        limiter.recordFailure("lky");
        limiter.reset("lky");

        // 清零后再错两次仍然不到阈值 3
        limiter.recordFailure("lky");
        limiter.recordFailure("lky");
        assertThatCode(() -> limiter.checkAllowed("lky")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 存储不可用时 fail-open：抛异常也要放行，但必须计数（降级不能无声无息）")
    void storeFailureFailsOpenAndCounts() {
        SecurityStore broken = mock(SecurityStore.class);
        doThrow(new IllegalStateException("Redis 不可用")).when(broken).exists(anyString());
        doThrow(new IllegalStateException("Redis 不可用")).when(broken).increment(anyString(), any(Duration.class));
        doThrow(new IllegalStateException("Redis 不可用")).when(broken).delete(any(String[].class));

        NexusSecurityProperties properties = new NexusSecurityProperties();
        LoginAttemptLimiter degraded = new LoginAttemptLimiter(broken, properties, observability);

        // 连续失败到超过任何合理阈值，checkAllowed 依然放行 —— Redis 挂了不能把所有人挡在门外
        for (int i = 0; i < 10; i++) {
            assertThatCode(() -> degraded.recordFailure("lky")).doesNotThrowAnyException();
        }
        assertThatCode(() -> degraded.checkAllowed("lky")).doesNotThrowAnyException();
        assertThatCode(() -> degraded.reset("lky")).doesNotThrowAnyException();

        verify(observability, org.mockito.Mockito.atLeastOnce())
                .increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("冻结不过期：15 分钟内 checkAllowed 一直拦（用长 TTL 验证存在性语义）")
    void lockPersistsWithinWindow() {
        limiter.recordFailure("lky");
        limiter.recordFailure("lky");
        limiter.recordFailure("lky");

        assertThat(store.exists("nexus:auth:ll:lky")).isTrue();
        assertThatThrownBy(() -> limiter.checkAllowed("lky")).isInstanceOf(BusinessException.class);
    }
}
