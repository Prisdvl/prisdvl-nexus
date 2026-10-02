package com.lky.nexus.app.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.OptionalLong;

/**
 * 每用户每日配额（5.6）：请求次数 + token 用量。
 *
 * <p>这是本期唯一 **fail-closed** 的组件：存储不可用时<b>拒绝</b>请求（报 7002），
 * 而不是放行。与限流的 fail-open 相反，因为这两件事的失败后果不对称——
 * 限流失效是"有人多刷了几次"，配额失效是"有人把真实的模型费用烧光"。
 * 用户已拍板采用这个口径，所以这里不是疏漏，是选择；代价是 Redis 全挂时
 * 聊天整体不可用，见 ADR-020 的"诚实边界"。
 *
 * <p><b>拒绝文案要能区分两种超限原因</b>："已用尽"与"配额服务不可用"。
 * 两者都是 7002/403，但用户能做的事完全不同：一个是等明天，一个是等运维。
 * 回同一句话会制造一个查不出原因的工单。
 */
@Component
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private static final String REQUEST_KEY_PREFIX = "nexus:q:req:";
    private static final String TOKEN_KEY_PREFIX = "nexus:q:tok:";

    /** 日期片，键的一部分：跨日即新键，旧键靠 TTL 自然消失。 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** TTL 的最小值：午夜前后算出的"到今日结束"可能只剩几毫秒，0 TTL 会让键立刻消失。 */
    private static final Duration MIN_TTL = Duration.ofMinutes(1);

    private final SecurityStore store;
    private final NexusSecurityProperties properties;
    private final ObservabilityRegistry observability;

    public QuotaService(SecurityStore store,
                        NexusSecurityProperties properties,
                        ObservabilityRegistry observability) {
        this.store = store;
        this.properties = properties;
        this.observability = observability;
    }

    /**
     * 占用一次"模型入口请求"额度；超限抛 {@code QUOTA_EXHAUSTED(7002)} / 403。
     *
     * <p>先自增再判断，而不是"先读、没超再自增"：后者在并发下会让多个请求
     * 同时读到"还没超"然后一起放行。自增是原子的，它才是真正的预留动作；
     * 代价是被拒的请求也计入计数，所以对外展示时会用 {@code min(used, limit)} 收敛。
     */
    public void checkAndConsumeRequest(Long userId) {
        int limit = properties.getQuota().getDailyRequests();
        long used = increment(requestKey(userId), "占用请求额度");
        if (used > limit) {
            throw new BusinessException(ErrorCode.QUOTA_EXHAUSTED,
                    "今日问答次数已用尽（上限 " + limit + " 次/天），将于次日 00:00 重置");
        }
    }

    /**
     * 检查当日 token 预算；超限抛 {@code QUOTA_EXHAUSTED(7002)} / 403。
     *
     * <p>这里只读不写：token 用量由模型调用**之后**的 {@link #recordTokens} 记账，
     * 事前无法知道这次会花多少。所以它是"事后闸门"——上一轮把额度花超了，
     * 这一轮在入口就被拦住（而不是花到一半断掉）。
     */
    public void checkTokenBudget(Long userId) {
        long limit = properties.getQuota().getDailyTokens();
        long used = read(tokenKey(userId));
        if (used >= limit) {
            throw new BusinessException(ErrorCode.QUOTA_EXHAUSTED,
                    "今日 token 额度已用尽（上限 " + limit + " tokens/天），将于次日 00:00 重置");
        }
    }

    /**
     * 记账本次模型调用的 token 消耗。
     *
     * <p><b>尽力而为，绝不抛异常</b>：模型已经调用了，花掉的钱不会因为记账失败而回来
     * ——此时抛异常只会把一个"少记了一笔账"变成"用户看到 500，但费用照样产生"。
     * 失败只计数（{@link ObservabilityRegistry#QUOTA_RECORD_FAILED}）。
     */
    public void recordTokens(Long userId, int promptTokens, int completionTokens) {
        if (userId == null) {
            return;
        }
        long delta = (long) promptTokens + completionTokens;
        if (delta <= 0) {
            return;
        }
        try {
            store.add(tokenKey(userId), delta, ttlUntilEndOfDay());
        } catch (RuntimeException ex) {
            observability.increment(ObservabilityRegistry.QUOTA_RECORD_FAILED);
            log.warn("token 用量记账失败（费用已产生，账少记了） userId={}, delta={}", userId, delta, ex);
        }
    }

    /**
     * 一次模型调用没有归属到任何用户：调用方忘了把 userId 放进 Advisor 上下文。
     *
     * <p>单独成一个方法是因为它**不是**记账失败——是"这个功能依赖每个入口都传 userId，
     * 漏一个就会静默少计"。这条计数让缺口可见，而不是等对账时才发现。
     */
    public void recordUnattributedUsage() {
        observability.increment(ObservabilityRegistry.QUOTA_USAGE_UNATTRIBUTED);
    }

    /** 只读快照，供 {@code GET /api/users/me/quota} 展示——让配额从隐形旋钮变成可见的东西。 */
    public Snapshot snapshot(Long userId) {
        long requestUsed = Math.min(read(requestKey(userId)), properties.getQuota().getDailyRequests());
        long tokenUsed = read(tokenKey(userId));
        return new Snapshot(requestUsed,
                properties.getQuota().getDailyRequests(),
                tokenUsed,
                properties.getQuota().getDailyTokens(),
                LocalDate.now().plusDays(1).atStartOfDay());
    }

    private long increment(String key, String action) {
        try {
            return store.increment(key, ttlUntilEndOfDay());
        } catch (RuntimeException ex) {
            observability.increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
            log.error("配额存储不可用，拒绝本次请求（fail-closed） action={}, key={}", action, key, ex);
            throw new BusinessException(ErrorCode.QUOTA_EXHAUSTED, "配额服务暂时不可用，请稍后再试");
        }
    }

    private long read(String key) {
        try {
            OptionalLong value = store.get(key);
            return value.isPresent() ? value.getAsLong() : 0L;
        } catch (RuntimeException ex) {
            observability.increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
            log.error("配额存储不可用，拒绝本次请求（fail-closed） key={}", key, ex);
            throw new BusinessException(ErrorCode.QUOTA_EXHAUSTED, "配额服务暂时不可用，请稍后再试");
        }
    }

    private String requestKey(Long userId) {
        return REQUEST_KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY);
    }

    private String tokenKey(Long userId) {
        return TOKEN_KEY_PREFIX + userId + ":" + LocalDate.now().format(DAY);
    }

    /** 距次日 00:00 的时长——配额的边界是自然日，不是"从首次使用起 24 小时"。 */
    private Duration ttlUntilEndOfDay() {
        Duration ttl = Duration.between(LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay());
        return ttl.compareTo(MIN_TTL) < 0 ? MIN_TTL : ttl;
    }

    /** 配额快照（只读视图）。 */
    public record Snapshot(long requestUsed,
                           long requestLimit,
                           long tokenUsed,
                           long tokenLimit,
                           LocalDateTime resetAt) {
    }
}
