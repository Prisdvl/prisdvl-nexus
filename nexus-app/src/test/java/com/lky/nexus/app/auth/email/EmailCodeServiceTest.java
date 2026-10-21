package com.lky.nexus.app.auth.email;

import com.lky.nexus.app.security.InMemorySecurityStore;
import com.lky.nexus.app.security.NexusSecurityProperties;
import com.lky.nexus.app.security.RateLimiter;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 邮箱验证码领域服务：跑在 MemoryEmailCodeStore 上，不依赖 Redis / Docker。
 *
 * <p>明文验证码仅从测试用 {@link RecordingEmailSender} 捕获，不依赖 {@code send()} 返回值。
 */
class EmailCodeServiceTest {

    private MemoryEmailCodeStore store;
    private RecordingEmailSender sender;
    private EmailCodeService service;
    private NexusEmailProperties properties;

    @BeforeEach
    void setUp() {
        store = new MemoryEmailCodeStore();
        sender = new RecordingEmailSender();
        properties = new NexusEmailProperties();
        properties.getCode().setLength(6);
        properties.getCode().setExpire(Duration.ofMinutes(5));
        properties.getCode().setMaxAttempts(5);
        properties.getCode().setCooldown(Duration.ofSeconds(60));
        properties.getCode().setIpPerHour(100);

        service = new EmailCodeService(store, sender, rateLimiter(), properties);
    }

    @Test
    @DisplayName("正常发送：落库 + 调用 EmailSender，码为 6 位数字；send 不返回明文")
    void sendIssuesNumericCodeAndDelegatesToSender() {
        service.send("User@Example.COM", EmailPurpose.REGISTER, "1.2.3.4");

        String code = sender.lastCode();
        assertThat(code).matches("\\d{6}");
        assertThat(sender.events).hasSize(1);
        assertThat(sender.events.get(0).email()).isEqualTo("user@example.com");
        assertThat(sender.events.get(0).purpose()).isEqualTo(EmailPurpose.REGISTER);
        assertThat(service.peekTtl("user@example.com", EmailPurpose.REGISTER).isPresent()).isTrue();
    }

    @Test
    @DisplayName("正确验证码验证成功")
    void correctCodeVerifies() {
        service.send("a@b.com", EmailPurpose.RESET_PASSWORD, "10.0.0.1");
        service.verify("a@b.com", EmailPurpose.RESET_PASSWORD, sender.lastCode());
    }

    @Test
    @DisplayName("验证成功后再次验证失败（一次性消费）")
    void secondVerifyFailsAfterSuccess() {
        service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1");
        String code = sender.lastCode();
        service.verify("a@b.com", EmailPurpose.REGISTER, code);

        assertThatThrownBy(() -> service.verify("a@b.com", EmailPurpose.REGISTER, code))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMAIL_CODE_INVALID.getCode());
    }

    @Test
    @DisplayName("错误验证码增加次数，未达上限仍为 EMAIL_CODE_INVALID")
    void wrongCodeIncrementsAttempts() {
        service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1");

        assertThatThrownBy(() -> service.verify("a@b.com", EmailPurpose.REGISTER, "000000"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMAIL_CODE_INVALID.getCode());

        assertThat(service.peekTtl("a@b.com", EmailPurpose.REGISTER).isPresent()).isTrue();
    }

    @Test
    @DisplayName("超过最大次数后作废，返回 EMAIL_CODE_TOO_MANY_ATTEMPTS")
    void exceedingMaxAttemptsExhaustsCode() {
        properties.getCode().setMaxAttempts(3);
        service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1");

        assertThatThrownBy(() -> service.verify("a@b.com", EmailPurpose.REGISTER, "111111"))
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMAIL_CODE_INVALID.getCode());
        assertThatThrownBy(() -> service.verify("a@b.com", EmailPurpose.REGISTER, "222222"))
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMAIL_CODE_INVALID.getCode());
        assertThatThrownBy(() -> service.verify("a@b.com", EmailPurpose.REGISTER, "333333"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMAIL_CODE_TOO_MANY_ATTEMPTS.getCode());

        assertThat(service.peekTtl("a@b.com", EmailPurpose.REGISTER).isPresent()).isFalse();
    }

    @Test
    @DisplayName("TTL 落在配置的 expire 附近")
    void ttlMatchesConfiguredExpire() {
        properties.getCode().setExpire(Duration.ofMinutes(5));
        service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1");

        long ttl = service.peekTtl("a@b.com", EmailPurpose.REGISTER).getAsLong();
        assertThat(ttl).isBetween(Duration.ofMinutes(4).toMillis(), Duration.ofMinutes(5).toMillis());
    }

    @Test
    @DisplayName("邮箱大小写与首尾空白统一")
    void emailNormalizedForSendAndVerify() {
        service.send("  Foo@Bar.Com ", EmailPurpose.REGISTER, "10.0.0.1");
        service.verify("foo@bar.com", EmailPurpose.REGISTER, sender.lastCode());
    }

    @Test
    @DisplayName("空邮箱非法 → PARAM_INVALID")
    void blankEmailRejected() {
        assertThatThrownBy(() -> service.send("   ", EmailPurpose.REGISTER, "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("60 秒冷却内重复发送 → RATE_LIMITED")
    void cooldownBlocksResend() {
        service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1");

        assertThatThrownBy(() -> service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());

        assertThat(sender.events).hasSize(1);
    }

    @Test
    @DisplayName("并发验证同一码：仅一个成功")
    void concurrentVerifyOnlyOneSucceeds() throws Exception {
        service.send("race@example.com", EmailPurpose.REGISTER, "10.0.0.1");
        String code = sender.lastCode();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.verify("race@example.com", EmailPurpose.REGISTER, code);
                    success.incrementAndGet();
                } catch (BusinessException ex) {
                    failure.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(failure.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("IP 维限流：超过 ip-per-hour 抛 RATE_LIMITED")
    void ipRateLimitApplies() {
        properties.getCode().setIpPerHour(2);
        properties.getCode().setCooldown(Duration.ofMillis(1));

        service.send("a1@b.com", EmailPurpose.REGISTER, "9.9.9.9");
        sleep(5);
        service.send("a2@b.com", EmailPurpose.REGISTER, "9.9.9.9");
        sleep(5);

        assertThatThrownBy(() -> service.send("a3@b.com", EmailPurpose.REGISTER, "9.9.9.9"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    @Test
    @DisplayName("发信失败：删除验证码并释放冷却，允许立即重发；异常不吞掉")
    void sendFailureRollsBackCodeAndCooldown() {
        // EmailSender 现在有两个方法，lambda 不再够用；这里只关心 sendCode 抛错
        EmailSender failing = new EmailSender() {
            @Override
            public void sendCode(String email, String code, EmailPurpose purpose, Duration expire) {
                throw new IllegalStateException("smtp down");
            }

            @Override
            public void sendNotice(String email, String title, String body) {
                throw new IllegalStateException("not used");
            }
        };
        EmailCodeService failingService = new EmailCodeService(store, failing, rateLimiter(), properties);

        assertThatThrownBy(() -> failingService.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
                    assertThat(be.getCause()).isInstanceOf(IllegalStateException.class);
                });

        assertThat(failingService.peekTtl("a@b.com", EmailPurpose.REGISTER).isPresent()).isFalse();

        service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1");
        assertThat(sender.lastCode()).matches("\\d{6}");
    }

    @Test
    @DisplayName("deleteIfCodeMatches 不会误删不同码（发信失败回滚的第二道防线）")
    void deleteIfCodeMatchesKeepsDifferentCode() {
        store.save(EmailPurpose.REGISTER, "a@b.com", "111111", Duration.ofMinutes(5));
        store.deleteIfCodeMatches(EmailPurpose.REGISTER, "a@b.com", "999999");
        assertThat(store.ttlMillis(EmailPurpose.REGISTER, "a@b.com")).isPresent();

        store.deleteIfCodeMatches(EmailPurpose.REGISTER, "a@b.com", "111111");
        assertThat(store.ttlMillis(EmailPurpose.REGISTER, "a@b.com")).isEmpty();
    }

    @Test
    @DisplayName("过期验证码在校验时被拒绝")
    void expiredCodeRejectedOnVerify() {
        properties.getCode().setExpire(Duration.ofMillis(30));
        properties.getCode().setCooldown(Duration.ofMillis(1));
        service.send("a@b.com", EmailPurpose.REGISTER, "10.0.0.1");
        String code = sender.lastCode();
        sleep(50);

        assertThatThrownBy(() -> service.verify("a@b.com", EmailPurpose.REGISTER, code))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMAIL_CODE_INVALID.getCode());
    }

    @Test
    @DisplayName("IP 限流失败不占用邮箱冷却（限流在冷却之前）")
    void ipLimitFailureDoesNotConsumeCooldown() {
        properties.getCode().setIpPerHour(1);
        service.send("first@b.com", EmailPurpose.REGISTER, "8.8.8.8");

        assertThatThrownBy(() -> service.send("second@b.com", EmailPurpose.REGISTER, "8.8.8.8"))
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());

        service.send("second@b.com", EmailPurpose.REGISTER, "1.1.1.1");
        assertThat(sender.lastCode()).matches("\\d{6}");
    }

    private RateLimiter rateLimiter() {
        return new RateLimiter(
                new InMemorySecurityStore(),
                new NexusSecurityProperties(),
                mock(ObservabilityRegistry.class));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("用途隔离：REGISTER 码不能用于 VERIFY_EMAIL")
    void purposeIsolationBetweenRegisterAndVerifyEmail() {
        properties.getCode().setCooldown(Duration.ofMillis(1));
        service.send("iso@b.com", EmailPurpose.REGISTER, "10.0.0.1");
        String registerCode = sender.lastCode();
        sleep(5);

        assertThatThrownBy(() -> service.verify("iso@b.com", EmailPurpose.VERIFY_EMAIL, registerCode))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMAIL_CODE_INVALID.getCode());

        service.send("iso@b.com", EmailPurpose.VERIFY_EMAIL, "10.0.0.1");
        service.verify("iso@b.com", EmailPurpose.VERIFY_EMAIL, sender.lastCode());
    }
}
