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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 每日配额的单元测试。
 *
 * <p>与限流器相反，这里的重点是 <b>fail-closed</b>：存储不可用时必须**拒绝**。
 * 这两个类在同一个故障下的行为不同，所以必须各自有一条用例钉住，
 * 否则很容易在"统一处置"的重构里被改成一致——而那正好会让配额失去意义。
 */
class QuotaServiceTest {

    private SecurityStore store;
    private ObservabilityRegistry observability;
    private NexusSecurityProperties properties;
    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        properties = new NexusSecurityProperties();
        properties.getQuota().setDailyRequests(3);
        properties.getQuota().setDailyTokens(1000L);

        store = new InMemorySecurityStore();
        observability = mock(ObservabilityRegistry.class);
        quotaService = new QuotaService(store, properties, observability);
    }

    @Test
    @DisplayName("请求额度：上限内放行，第 limit+1 次报 7002")
    void dailyRequestLimitEnforced() {
        quotaService.checkAndConsumeRequest(1L);
        quotaService.checkAndConsumeRequest(1L);
        quotaService.checkAndConsumeRequest(1L);

        assertThatThrownBy(() -> quotaService.checkAndConsumeRequest(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("次数已用尽")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.QUOTA_EXHAUSTED.getCode());
    }

    @Test
    @DisplayName("额度按用户隔离")
    void quotaIsPerUser() {
        for (int i = 0; i < 3; i++) {
            quotaService.checkAndConsumeRequest(1L);
        }
        assertThatThrownBy(() -> quotaService.checkAndConsumeRequest(1L)).isInstanceOf(BusinessException.class);
        assertThatCode(() -> quotaService.checkAndConsumeRequest(2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("token 预算：记账累加，达到上限后被拦")
    void tokenBudgetEnforced() {
        quotaService.checkTokenBudget(1L);
        quotaService.recordTokens(1L, 400, 400);   // 累计 800，仍未到 1000

        assertThatCode(() -> quotaService.checkTokenBudget(1L)).doesNotThrowAnyException();

        quotaService.recordTokens(1L, 100, 100);   // 累计 1000 → 到顶
        assertThatThrownBy(() -> quotaService.checkTokenBudget(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("token 额度已用尽");
    }

    @Test
    @DisplayName("★ 记账是按 delta 累加，不是每次 +1（token 数量级的差异会直接决定配额是否形同虚设）")
    void recordTokensAddsActualAmount() {
        quotaService.recordTokens(1L, 300, 200);

        assertThat(quotaService.snapshot(1L).tokenUsed()).isEqualTo(500L);
    }

    @Test
    @DisplayName("usage 为 0（供应商不回传）不写键，不产生无意义的空计数")
    void zeroUsageIsIgnored() {
        quotaService.recordTokens(1L, 0, 0);

        assertThat(quotaService.snapshot(1L).tokenUsed()).isZero();
    }

    @Test
    @DisplayName("★ 记账失败只计数、不抛：模型已经调用了，抛异常只会让用户看到 500 而费用照付")
    void recordFailureIsSwallowedAndCounted() {
        SecurityStore broken = mock(SecurityStore.class);
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(broken).add(anyString(), anyLong(), any(Duration.class));
        QuotaService degraded = new QuotaService(broken, properties, observability);

        assertThatCode(() -> degraded.recordTokens(1L, 100, 100)).doesNotThrowAnyException();

        verify(observability).increment(ObservabilityRegistry.QUOTA_RECORD_FAILED);
    }

    @Test
    @DisplayName("★ 存储不可用时 fail-closed：拒绝并如实说是「服务不可用」，不是「额度用尽」")
    void storeFailureFailsClosedWithDistinctMessage() {
        SecurityStore broken = mock(SecurityStore.class);
        // increment 是接口上的 default 方法：Mockito 不会执行 default 实现，
        // 所以必须 stub 它本身（只 stub add 的话 mock 直接返回 0，测试会假通过）
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(broken).increment(anyString(), any(Duration.class));
        doThrow(new IllegalStateException("Redis 不可用")).when(broken).get(anyString());
        QuotaService degraded = new QuotaService(broken, properties, observability);

        assertThatThrownBy(() -> degraded.checkAndConsumeRequest(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂时不可用")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.QUOTA_EXHAUSTED.getCode());

        assertThatThrownBy(() -> degraded.checkTokenBudget(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂时不可用");

        verify(observability, org.mockito.Mockito.atLeastOnce())
                .increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("快照：被拒的请求也计入计数，但对外展示收敛到上限（不显示 305/3 这种数）")
    void snapshotCapsUsageAtLimit() {
        for (int i = 0; i < 5; i++) {
            try {
                quotaService.checkAndConsumeRequest(1L);
            } catch (BusinessException ignored) {
                // 前 3 次成功、后 2 次被拒，但计数已经走到 5
            }
        }

        QuotaService.Snapshot snapshot = quotaService.snapshot(1L);
        assertThat(snapshot.requestUsed()).isEqualTo(3L);
        assertThat(snapshot.requestLimit()).isEqualTo(3L);
        assertThat(snapshot.resetAt()).isNotNull();
    }

    @Test
    @DisplayName("无归属用量单独计数：它和「记账失败」是两回事")
    void unattributedUsageIsCountedSeparately() {
        quotaService.recordUnattributedUsage();

        verify(observability).increment(ObservabilityRegistry.QUOTA_USAGE_UNATTRIBUTED);
    }
}
