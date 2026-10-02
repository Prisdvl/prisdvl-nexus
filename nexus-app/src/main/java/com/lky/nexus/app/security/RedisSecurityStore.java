package com.lky.nexus.app.security;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

/**
 * Redis 实现的 {@link SecurityStore}（{@code nexus.security.redis-enabled=true} 时启用）。
 *
 * <p>用 {@link StringRedisTemplate} 而不是 {@code RedisTemplate<String,Object>}：
 * 这里存的全是十进制整数，字符串模板避免任何序列化器配置带来的"值读出来带引号/带类型前缀"
 * 这类只在换实现时才暴露的问题。
 *
 * <p>{@code INCR} + {@code EXPIRE} 的组合是固定窗口计数的标准做法，但有个坑：
 * <b>{@code EXPIRE} 只能在第一次 {@code INCR} 之后设置</b>（否则键还不存在，过期设置无处可挂）。
 * 所以这里判断"结果是否为 1"。两个命令之间理论上存在极小窗口，如果进程正好在此刻崩溃，
 * 键会永久存在（不过期）——对本项目是可接受的（最坏情况是某个用户当天的计数不清零），
 * 不值得为它引入 Lua 脚本。
 */
public class RedisSecurityStore implements SecurityStore {

    private final StringRedisTemplate redis;

    public RedisSecurityStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long add(String key, long delta, Duration ttl) {
        Long value = redis.opsForValue().increment(key, delta);
        if (value == null) {
            // 理论上 INCRBY 一定返回值；真发生时按"存储不可用"处理，由调用方决定 fail-open/closed
            throw new IllegalStateException("Redis INCRBY 返回空值，key=" + key);
        }
        // 返回值恰好等于 delta ⇔ 这个键原本不存在（从 0 加起）⇔ 这是窗口内的第一次写入。
        // 不能写成 value == 1：token 记账的 delta 是上千，那会让 EXPIRE 永不执行、键永不过期。
        if (value == delta) {
            redis.expire(key, ttl);
        }
        return value;
    }

    @Override
    public OptionalLong get(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(raw));
        } catch (NumberFormatException ex) {
            // 键被别的写入方占用（或人工写坏）：当成"没有这个键"，不能让认证流程 500
            return OptionalLong.empty();
        }
    }

    @Override
    public void put(String key, long value, Duration ttl) {
        redis.opsForValue().set(key, Long.toString(value), ttl);
    }

    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    @Override
    public void delete(String... keys) {
        if (keys.length > 0) {
            redis.delete(List.of(keys));
        }
    }
}
