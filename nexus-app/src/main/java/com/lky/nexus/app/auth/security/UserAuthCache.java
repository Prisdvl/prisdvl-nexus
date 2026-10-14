package com.lky.nexus.app.auth.security;

import com.lky.nexus.app.auth.config.NexusJwtProperties;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.security.SecurityStore;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * 令牌版本缓存：把"这个用户当前的 token_version 是多少"从每请求查库变成查缓存。
 *
 * <p><b>为什么是 cache-aside（读穿 + 主动失效），而不是 write-through</b>：
 * 写穿要做"读旧值 → +1 → 写回"，并发两次改密码会有一个把旧版本写回去；
 * 失效（{@code delete}）没有这个竞态，代价只是改密后的第一个请求多读一次库。
 *
 * <p><b>故障语义是本类最要紧的一点</b>：读失败（Redis 挂了）**回落查库**，
 * 而不是 fail-open 也不是 fail-closed。DB 是版本号的事实来源，缓存只是缓存，
 * 所以"Redis 不可用"在这里是**降级**而不是"放弃校验"或"拒绝所有请求"——
 * 这也是为什么吊销这件事没有像限流那样落入 fail-open/closed 的二选一。
 *
 * <p>代价说清楚：ADR-018 第 4 条写的是"无状态鉴权不在每个请求上查库"，
 * 从本期起变成"正常情况下每请求一次 Redis GET，故障时每请求一次 SELECT"。
 * 这是为"改密码立即失效"付的账，见 docs/decisions.md ADR-020。
 *
 * <p>缓存失效失败的后果是<b>有界</b>的：旧令牌最多再多活一个 TTL
 * （{@code nexus.jwt.token-version-cache-ttl}，默认 5 分钟）。这条路径会计数
 * （{@link ObservabilityRegistry#SECURITY_TOKEN_VERSION_INVALIDATE_FAILED}），
 * 不会静默。
 *
 * <p>禁用账号：缓存未命中时查库发现 {@code status != 1} 返回 null（与 refresh 拒绝一致）。
 * 缓存命中路径只存版本号，禁用后最多仍有一个 cache TTL 窗口——与改密失效失败同量级。
 */
@Component
public class UserAuthCache {

    private static final Logger log = LoggerFactory.getLogger(UserAuthCache.class);

    private static final String KEY_PREFIX = "nexus:auth:tv:";

    private final SysUserMapper sysUserMapper;
    private final SecurityStore securityStore;
    private final NexusJwtProperties jwtProperties;
    private final ObservabilityRegistry observability;

    public UserAuthCache(SysUserMapper sysUserMapper,
                         SecurityStore securityStore,
                         NexusJwtProperties jwtProperties,
                         ObservabilityRegistry observability) {
        this.sysUserMapper = sysUserMapper;
        this.securityStore = securityStore;
        this.jwtProperties = jwtProperties;
        this.observability = observability;
    }

    /**
     * 该用户当前生效的令牌版本。
     *
     * @return 版本号；<b>用户不存在返回 {@code null}</b>（调用方据此判定令牌无效）
     */
    public Long expectedTokenVersion(Long userId) {
        OptionalLong cached = readCache(userId);
        if (cached.isPresent()) {
            return cached.getAsLong();
        }

        SysUserPO user = sysUserMapper.selectById(userId);
        if (user == null) {
            // 令牌合法但用户已被删除：不缓存，下次请求仍然查库（不会有"复活"的窗口）
            return null;
        }
        // 与登录 / refresh 一致：禁用账号不得继续通过 access JWT（缓存未命中路径）
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            return null;
        }
        long version = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
        writeCache(userId, version);
        return version;
    }

    /** 改密码后调用：让缓存里的旧版本立刻作废（尽力而为，失败只计数）。 */
    public void invalidate(Long userId) {
        try {
            securityStore.delete(key(userId));
        } catch (RuntimeException ex) {
            // 最坏情况：旧令牌在缓存 TTL 内继续有效。计数让它可见，而不是"看起来吊销成功了"
            observability.increment(ObservabilityRegistry.SECURITY_TOKEN_VERSION_INVALIDATE_FAILED);
            log.warn("令牌版本缓存失效失败（旧令牌将在缓存 TTL 内继续有效） userId={}", userId, ex);
        }
    }

    private OptionalLong readCache(Long userId) {
        try {
            return securityStore.get(key(userId));
        } catch (RuntimeException ex) {
            // 降级：回落查库。这里刻意用 debug——Redis 挂掉时这是每请求一次的高频路径，
            // WARN 会把日志刷爆；"Redis 不可用"这个事实由计数与 Redis 自身的告警表达
            observability.increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
            log.debug("令牌版本缓存读取失败，回落查库 userId={}", userId, ex);
            return OptionalLong.empty();
        }
    }

    private void writeCache(Long userId, long version) {
        try {
            securityStore.put(key(userId), version, jwtProperties.getTokenVersionCacheTtl());
        } catch (RuntimeException ex) {
            observability.increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
            log.debug("令牌版本缓存写入失败，本次请求仍已用真实值校验 userId={}", userId, ex);
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
