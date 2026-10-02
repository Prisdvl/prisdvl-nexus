package com.lky.nexus.app.security;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * 安全模块用到的键值存储端口（5.6）：**带过期的计数器** + **小值缓存**。
 *
 * <p>为什么需要一个端口而不是直接调 Redis：项目有三个 profile，其中
 * {@code standalone}（H2 内存）与 {@code test} 都明确"不依赖任何外部基础设施"。
 * 限流/配额/令牌版本如果硬依赖 Redis，这两个 profile 会直接起不来（全部测试连带失败）。
 * 既有范式就是 {@code StreamCancellationRegistry}：<b>接口 + 内存实现 + 未来 Redis 实现</b>。
 *
 * <p>两个实现的语义差别只有一条，必须知道：
 * <ul>
 *   <li>{@link InMemorySecurityStore} 是<b>单实例</b>的——多实例部署下各自计数，
 *       等于把额度乘上实例数；</li>
 *   <li>{@link RedisSecurityStore} 跨实例共享，但受 {@code maxmemory-policy} 影响，
 *       被淘汰即等于额度被重置。</li>
 * </ul>
 * 两者都不做持久化，重启即清。这些边界写在 docs/decisions.md ADR-020 里，不要当成 bug 去查。
 */
public interface SecurityStore {

    /**
     * 在原有值上加 {@code delta} 并返回新值；键不存在时从 0 开始，并设置过期时间。
     *
     * <p>需要 {@code delta} 而不只是 +1，是因为 token 记账一次要加上千个 token：
     * 用 +1 循环累加既慢又不原子，而"加多少就是多少"本身就是 Redis {@code INCRBY} 的语义。
     *
     * <p>固定窗口计数也靠它：键里带窗口标识（分钟 / 日期），过期即窗口结束。
     */
    long add(String key, long delta, Duration ttl);

    /** 自增 1；请求计数这类"一次一下"的场景直接用它。 */
    default long increment(String key, Duration ttl) {
        return add(key, 1L, ttl);
    }

    /** 读取当前值；键不存在或已过期返回空。 */
    OptionalLong get(String key);

    /** 写入一个值（会覆盖旧值与旧过期时间）。用于"冻结标记"这类只关心存在性的键。 */
    void put(String key, long value, Duration ttl);

    /** 键是否存在且未过期。 */
    boolean exists(String key);

    /** 删除若干键（不存在的键忽略）。 */
    void delete(String... keys);
}
