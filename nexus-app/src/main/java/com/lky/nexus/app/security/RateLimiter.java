package com.lky.nexus.app.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 请求频率限制（5.6）：固定窗口计数，用户维度（注册入口是 IP 维度）。
 *
 * <p><b>为什么用固定窗口而不是滑动窗口 / 令牌桶</b>：固定窗口只需要一次
 * {@code INCR}，而"键里带时间片"这件事本身就是窗口的边界——不需要额外存储、
 * 不需要 Lua、也不需要在两个实现（Redis / 内存）之间对齐更复杂的语义。
 * 代价是窗口边界处可能放过约两倍的请求（临界问题），对"防滥刷"这个目的完全够用；
 * 要更平滑的曲线，是限流算法的取舍，不是正确性问题。
 *
 * <p><b>fail-open</b>：存储不可用时放行并计数。理由是限流是"防滥用"，
 * 不是"业务正确性"——为它把正常用户挡在门外是本末倒置。
 * 注意这条只适用于限流；**每日配额是 fail-closed**（见 {@link QuotaService}），
 * 因为那直接对应"钱会不会被烧掉"。同一次故障里两者行为不同是刻意的，见 ADR-020。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String USER_KEY_PREFIX = "nexus:rl:u:";
    private static final String REGISTER_KEY_PREFIX = "nexus:rl:ip:";

    /** 分钟粒度的时间片（键的一部分，天然形成窗口边界）。 */
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 小时粒度的时间片（注册入口）。 */
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /**
     * 窗口键的存活时间比窗口本身长一档：键过期即"窗口结束"，
     * 但若 TTL 正好等于窗口，时钟边界上的请求可能命中一个刚被清掉的键——
     * 表现是"某一瞬间限流失效"。多留一档的代价只是几个多余的空键。
     */
    private static final Duration MINUTE_TTL = Duration.ofMinutes(2);
    private static final Duration HOUR_TTL = Duration.ofHours(2);

    private final SecurityStore store;
    private final NexusSecurityProperties properties;
    private final ObservabilityRegistry observability;

    public RateLimiter(SecurityStore store,
                       NexusSecurityProperties properties,
                       ObservabilityRegistry observability) {
        this.store = store;
        this.properties = properties;
        this.observability = observability;
    }

    /**
     * 用户维度限流：超过每分钟上限抛 {@code RATE_LIMITED(7001)} / 429。
     *
     * @param userId 只允许来自 JWT 的 {@code sub}（{@code CurrentUser.requireId()}）
     */
    public void checkUser(Long userId) {
        int limit = properties.getRateLimit().getPerMinute();
        long used = increment(USER_KEY_PREFIX + userId + ":" + LocalDateTime.now().format(MINUTE), MINUTE_TTL);
        if (used > limit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "请求过于频繁（每分钟上限 " + limit + " 次），请稍后再试");
        }
    }

    /**
     * 注册入口限流：按来源 IP，每小时上限。
     *
     * <p><b>不信任 {@code X-Forwarded-For}</b>：那个头是客户端可以随手伪造的，
     * 信任它等于让限流形同虚设（攻击者每次换一个伪造 IP 就是新桶）。
     * 代价是部署在反向代理之后时 {@code getRemoteAddr()} 会是代理地址，
     * 全体用户共用一个桶——届时必须先配可信代理链再启用，见 ADR-020。
     *
     * @param clientIp 由调用方从请求里取（{@code getRemoteAddr()}）
     */
    public void checkRegister(String clientIp) {
        int limit = properties.getRegister().getPerHourPerIp();
        long used = increment(REGISTER_KEY_PREFIX + clientIp + ":" + LocalDateTime.now().format(HOUR), HOUR_TTL);
        if (used > limit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "注册过于频繁（每小时上限 " + limit + " 次），请稍后再试");
        }
    }

    /** 计数；存储不可用时放行（返回 1，必然不超限）并计数。 */
    private long increment(String key, Duration ttl) {
        try {
            return store.increment(key, ttl);
        } catch (RuntimeException ex) {
            observability.increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
            log.warn("限流存储不可用（本次放行，fail-open） key={}", key, ex);
            return 1L;
        }
    }
}
