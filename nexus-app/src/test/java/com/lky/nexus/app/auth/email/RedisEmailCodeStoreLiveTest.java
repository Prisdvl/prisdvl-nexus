package com.lky.nexus.app.auth.email;

import com.lky.nexus.app.security.InMemorySecurityStore;
import com.lky.nexus.app.security.NexusSecurityProperties;
import com.lky.nexus.app.security.RateLimiter;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link RedisEmailCodeStore} 真实 Redis 集成测试（非 Memory、非静态审查）。
 *
 * <p><b>启用方式</b>（未设 {@code NEXUS_REDIS_LIVE=true} 时本类整体跳过；设了则必须连上 Redis，
 * 不会静默跳过用例）：
 * <pre>
 * # 优先：外接 Redis（本机已起 redis-server / compose redis）
 * $env:NEXUS_REDIS_LIVE='true'
 * $env:NEXUS_REDIS_HOST='127.0.0.1'   # 可选，默认 127.0.0.1
 * $env:NEXUS_REDIS_PORT='6379'        # 可选，默认 6379
 * mvn -pl nexus-app -am test "-Dtest=RedisEmailCodeStoreLiveTest"
 *
 * # 备选：Docker Desktop 可用时自动用 Testcontainers 拉 redis:8.10.2
 * $env:NEXUS_REDIS_LIVE='true'
 * # 不设 HOST 时：先试本机 6379，失败再试 Testcontainers
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_REDIS_LIVE", matches = "true")
class RedisEmailCodeStoreLiveTest {

    /** 与 deploy/docker-compose.yml 的 redis 服务同一 tag。 */
    private static final String REDIS_IMAGE = "redis:8.10.2";

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static String connectedVia;

    private RedisEmailCodeStore store;

    @BeforeAll
    static void connectRedis() {
        String host = envOr("NEXUS_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(envOr("NEXUS_REDIS_PORT", "6379"));
        boolean forceExternal = System.getenv("NEXUS_REDIS_HOST") != null
                || System.getenv("NEXUS_REDIS_PORT") != null;

        if (tryExternal(host, port)) {
            connectedVia = "external " + host + ":" + port;
            return;
        }
        if (forceExternal) {
            throw new IllegalStateException(
                    "NEXUS_REDIS_LIVE=true 且指定了 HOST/PORT，但无法 PING "
                            + host + ":" + port + "。请先启动 Redis。");
        }
        if (tryTestcontainers()) {
            connectedVia = "testcontainers " + REDIS_IMAGE;
            return;
        }
        throw new IllegalStateException("""
                NEXUS_REDIS_LIVE=true 但未能连接 Redis：
                1) 本机 %s:%d 不可达；
                2) Testcontainers/Docker 也不可用。
                请启动 redis-server 或 Docker Desktop 后重试。
                """.formatted(host, port));
    }

    private static boolean tryExternal(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        try {
            factory.afterPropertiesSet();
            try (var connection = factory.getConnection()) {
                String pong = connection.ping();
                if (pong == null || !pong.equalsIgnoreCase("PONG")) {
                    factory.destroy();
                    return false;
                }
            }
            StringRedisTemplate probe = new StringRedisTemplate(factory);
            probe.afterPropertiesSet();
            connectionFactory = factory;
            template = probe;
            return true;
        } catch (RuntimeException ex) {
            try {
                factory.destroy();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            return false;
        }
    }

    @SuppressWarnings("resource")
    private static boolean tryTestcontainers() {
        try {
            redisContainer = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));
            redisContainer.start();
            return tryExternal(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        } catch (RuntimeException ex) {
            if (redisContainer != null) {
                try {
                    redisContainer.stop();
                } catch (RuntimeException ignored) {
                    // best-effort
                }
                redisContainer = null;
            }
            return false;
        }
    }

    private static String envOr(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        assertThat(connectedVia).as("Redis 连接方式").isNotBlank();
        store = new RedisEmailCodeStore(template);
        var keys = template.keys("nexus:auth:email-code*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    @Test
    @DisplayName("保存后 Key 带有正确 TTL")
    void saveSetsTtl() {
        store.save(EmailPurpose.REGISTER, "a@b.com", "123456", Duration.ofMinutes(5));

        long ttl = store.ttlMillis(EmailPurpose.REGISTER, "a@b.com").orElseThrow();
        assertThat(ttl).isBetween(Duration.ofMinutes(4).toMillis(), Duration.ofMinutes(5).toMillis());

        String raw = template.opsForValue().get("nexus:auth:email-code:REGISTER:a@b.com");
        assertThat(raw).isEqualTo("123456:0");
    }

    @Test
    @DisplayName("正确码校验成功并删除；再次校验失败")
    void correctCodeConsumedOnce() {
        store.save(EmailPurpose.REGISTER, "a@b.com", "654321", Duration.ofMinutes(5));

        assertThat(store.verifyAndConsume(EmailPurpose.REGISTER, "a@b.com", "654321", 5))
                .isEqualTo(EmailCodeVerifyResult.SUCCESS);
        assertThat(store.ttlMillis(EmailPurpose.REGISTER, "a@b.com")).isEmpty();
        assertThat(store.verifyAndConsume(EmailPurpose.REGISTER, "a@b.com", "654321", 5))
                .isEqualTo(EmailCodeVerifyResult.MISSING);
    }

    @Test
    @DisplayName("错误码增加 attempts；达上限后失效")
    void wrongCodeIncrementsThenExhausts() {
        store.save(EmailPurpose.REGISTER, "a@b.com", "111111", Duration.ofMinutes(5));

        assertThat(store.verifyAndConsume(EmailPurpose.REGISTER, "a@b.com", "000000", 3))
                .isEqualTo(EmailCodeVerifyResult.MISMATCH);
        assertThat(template.opsForValue().get("nexus:auth:email-code:REGISTER:a@b.com"))
                .isEqualTo("111111:1");

        assertThat(store.verifyAndConsume(EmailPurpose.REGISTER, "a@b.com", "000001", 3))
                .isEqualTo(EmailCodeVerifyResult.MISMATCH);
        assertThat(store.verifyAndConsume(EmailPurpose.REGISTER, "a@b.com", "000002", 3))
                .isEqualTo(EmailCodeVerifyResult.EXHAUSTED);
        assertThat(store.ttlMillis(EmailPurpose.REGISTER, "a@b.com")).isEmpty();
    }

    @Test
    @DisplayName("过期验证码不能验证成功")
    void expiredCodeRejected() throws Exception {
        store.save(EmailPurpose.REGISTER, "a@b.com", "222222", Duration.ofMillis(80));
        Thread.sleep(120);

        assertThat(store.verifyAndConsume(EmailPurpose.REGISTER, "a@b.com", "222222", 5))
                .isEqualTo(EmailCodeVerifyResult.MISSING);
    }

    @Test
    @DisplayName("并发验证同一码：最多一个 SUCCESS")
    void concurrentVerifyOnlyOneSucceeds() throws Exception {
        store.save(EmailPurpose.REGISTER, "race@b.com", "777777", Duration.ofMinutes(5));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                EmailCodeVerifyResult r = store.verifyAndConsume(
                        EmailPurpose.REGISTER, "race@b.com", "777777", 5);
                if (r == EmailCodeVerifyResult.SUCCESS) {
                    success.incrementAndGet();
                } else {
                    other.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(other.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("PTTL 边界：错码更新不会留下永久 Key")
    void pttlBoundaryDoesNotCreatePersistentKey() throws Exception {
        store.save(EmailPurpose.REGISTER, "a@b.com", "333333", Duration.ofMillis(50));
        Thread.sleep(55);

        EmailCodeVerifyResult result = store.verifyAndConsume(
                EmailPurpose.REGISTER, "a@b.com", "000000", 5);
        assertThat(result).isIn(EmailCodeVerifyResult.MISSING, EmailCodeVerifyResult.MISMATCH);

        Boolean hasKey = template.hasKey("nexus:auth:email-code:REGISTER:a@b.com");
        if (Boolean.TRUE.equals(hasKey)) {
            Long ttl = template.getExpire("nexus:auth:email-code:REGISTER:a@b.com", TimeUnit.MILLISECONDS);
            assertThat(ttl).isNotEqualTo(-1L);
        }
    }

    @Test
    @DisplayName("deleteIfCodeMatches 原子：只删匹配码，不删被覆盖的新码")
    void deleteIfCodeMatchesIsAtomic() {
        store.save(EmailPurpose.REGISTER, "a@b.com", "111111", Duration.ofMinutes(5));
        store.save(EmailPurpose.REGISTER, "a@b.com", "222222", Duration.ofMinutes(5));

        store.deleteIfCodeMatches(EmailPurpose.REGISTER, "a@b.com", "111111");
        assertThat(template.opsForValue().get("nexus:auth:email-code:REGISTER:a@b.com"))
                .isEqualTo("222222:0");

        store.deleteIfCodeMatches(EmailPurpose.REGISTER, "a@b.com", "222222");
        assertThat(store.ttlMillis(EmailPurpose.REGISTER, "a@b.com")).isEmpty();
    }

    @Test
    @DisplayName("与 MemoryEmailCodeStore 业务语义一致（同场景同结果）")
    void semanticParityWithMemoryStore() {
        MemoryEmailCodeStore memory = new MemoryEmailCodeStore();
        Duration ttl = Duration.ofMinutes(5);
        int max = 3;

        memory.save(EmailPurpose.RESET_PASSWORD, "p@q.com", "555555", ttl);
        store.save(EmailPurpose.RESET_PASSWORD, "p@q.com", "555555", ttl);

        assertThat(store.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "000000", max))
                .isEqualTo(memory.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "000000", max));
        assertThat(store.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "000001", max))
                .isEqualTo(memory.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "000001", max));
        assertThat(store.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "555555", max))
                .isEqualTo(memory.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "555555", max));
        assertThat(store.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "555555", max))
                .isEqualTo(memory.verifyAndConsume(EmailPurpose.RESET_PASSWORD, "p@q.com", "555555", max))
                .isEqualTo(EmailCodeVerifyResult.MISSING);
    }

    @Test
    @DisplayName("密码重置用途：Redis 下发送→冷却不重发→一次性确认消费")
    void resetPasswordSendCooldownAndConfirmOnRedis() {
        RecordingEmailSender sender = new RecordingEmailSender();
        NexusEmailProperties props = new NexusEmailProperties();
        props.getCode().setLength(6);
        props.getCode().setExpire(Duration.ofMinutes(5));
        props.getCode().setCooldown(Duration.ofSeconds(60));
        props.getCode().setMaxAttempts(5);
        props.getCode().setIpPerHour(1000);

        EmailCodeService service = new EmailCodeService(
                store,
                sender,
                new RateLimiter(new InMemorySecurityStore(), new NexusSecurityProperties(), mock(ObservabilityRegistry.class)),
                props);

        String email = "reset-live-" + System.nanoTime() + "@example.com";

        // 与 PasswordResetService 一致：先 IP 限流，再 sendSkippingIpLimit
        service.checkIpSendLimit("198.51.100.10");
        service.sendSkippingIpLimit(email, EmailPurpose.RESET_PASSWORD);
        assertThat(sender.events).hasSize(1);
        assertThat(sender.events.get(0).purpose()).isEqualTo(EmailPurpose.RESET_PASSWORD);
        String code = sender.lastCode();
        assertThat(code).matches("\\d{6}");

        long ttl = store.ttlMillis(EmailPurpose.RESET_PASSWORD, email).orElseThrow();
        assertThat(ttl).isBetween(Duration.ofMinutes(4).toMillis(), Duration.ofMinutes(5).toMillis());

        // 冷却命中：不重发（业务层 PasswordResetService 会吞 RATE_LIMITED）
        assertThatThrownBy(() -> service.sendSkippingIpLimit(email, EmailPurpose.RESET_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
        assertThat(sender.events).hasSize(1);

        service.verify(email, EmailPurpose.RESET_PASSWORD, code);
        assertThat(store.ttlMillis(EmailPurpose.RESET_PASSWORD, email)).isEmpty();
        assertThatThrownBy(() -> service.verify(email, EmailPurpose.RESET_PASSWORD, code))
                .isInstanceOf(BusinessException.class);
    }
}
