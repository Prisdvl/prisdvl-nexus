package com.lky.nexus.app.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 登录失败限制（5.6）：同一用户名连续失败到阈值即冻结一段时间。
 *
 * <p>三个调用点都在 {@code AuthServiceImpl.login}：进门前 {@link #checkAllowed}、
 * 凭证错误时 {@link #recordFailure}、登录成功时 {@link #reset}。
 *
 * <p><b>为什么是 fail-open</b>：存储（Redis）不可用时一律放行，只计数。
 * 反过来的话，Redis 一挂全体用户都登不进来——用"防止暴力破解"的功能
 * 制造一个"整个系统不可用"的故障，是拿更大的事故换更小的事故。
 * 放行不等于不设防：{@link ObservabilityRegistry#SECURITY_STORE_UNAVAILABLE} 会把
 * 这段"其实没在防"的时间记下来，而不是让降级悄悄发生。
 *
 * <p><b>为什么按用户名而不是按 IP</b>：用户原话就是 username → 失败次数。
 * 代价是**定向 DoS**——攻击者可以拿错密码把某个账号刷到冻结（这正是"锁定"的定义，
 * 只是触发者未必是本人）。缓解手段是窗口与冻结都取 15 分钟这种短值，
 * 而不是靠"不实现"。跨用户名的分布式撞库、以及代理后 IP 失真，本期都不覆盖，
 * 如实写在 docs/decisions.md ADR-020 的"诚实边界"里。
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    /** 失败计数键（失败次数，TTL = 窗口）。 */
    private static final String FAILURE_KEY_PREFIX = "nexus:auth:lf:";

    /** 冻结标记键（存在即冻结，TTL = 冻结时长）。 */
    private static final String LOCK_KEY_PREFIX = "nexus:auth:ll:";

    private final SecurityStore store;
    private final NexusSecurityProperties properties;
    private final ObservabilityRegistry observability;

    public LoginAttemptLimiter(SecurityStore store,
                               NexusSecurityProperties properties,
                               ObservabilityRegistry observability) {
        this.store = store;
        this.properties = properties;
        this.observability = observability;
    }

    /**
     * 该用户名当前是否被冻结；冻结中直接抛 {@code RATE_LIMITED(7001)} / 429。
     *
     * <p>注意它检查的是**用户名**，所以即便这次密码是对的也会被拦下——
     * 这正是"冻结"的含义，回一句"密码错误"反而会让攻击者以为还有机会。
     */
    public void checkAllowed(String username) {
        boolean locked;
        try {
            locked = store.exists(lockKey(username));
        } catch (RuntimeException ex) {
            degrade("check", username, ex);
            return;
        }
        if (locked) {
            long minutes = properties.getLogin().getLockDuration().toMinutes();
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "登录失败次数过多，账号已被暂时锁定，请 " + minutes + " 分钟后再试");
        }
    }

    /** 记一次失败；到达阈值时写冻结标记。 */
    public void recordFailure(String username) {
        NexusSecurityProperties.Login login = properties.getLogin();
        try {
            long failures = store.increment(failureKey(username), login.getWindow());
            if (failures >= login.getMaxFailures()) {
                store.put(lockKey(username), 1L, login.getLockDuration());
                log.warn("登录失败次数达到阈值，账号暂时锁定 username={}, 失败次数={}", username, failures);
            }
        } catch (RuntimeException ex) {
            degrade("recordFailure", username, ex);
        }
    }

    /** 登录成功后清零——否则"失败 4 次、成功 1 次"会一直累加到冻结。 */
    public void reset(String username) {
        try {
            store.delete(failureKey(username), lockKey(username));
        } catch (RuntimeException ex) {
            degrade("reset", username, ex);
        }
    }

    private String failureKey(String username) {
        return FAILURE_KEY_PREFIX + username;
    }

    private String lockKey(String username) {
        return LOCK_KEY_PREFIX + username;
    }

    private void degrade(String action, String username, RuntimeException ex) {
        observability.increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
        log.warn("登录失败限制不可用（本次放行，fail-open） action={}, username={}", action, username, ex);
    }
}
