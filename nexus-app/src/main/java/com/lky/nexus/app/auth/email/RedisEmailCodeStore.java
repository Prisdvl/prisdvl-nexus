package com.lky.nexus.app.auth.email;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Redis 验证码存储：值格式 {@code code:attempts}，校验用 Lua 保证原子消费。
 *
 * <p><b>为什么用 Lua 而不是 GETDEL</b>：GETDEL 只能「取到就删」，无法在码错误时
 * 保留键并 +attempts；错误路径也必须原子，否则并发错猜会冲掉计数。
 * Lua 在服务端一次完成：匹配 → DEL；不匹配 → attempts+1 / 达上限 DEL。
 */
public class RedisEmailCodeStore implements EmailCodeStore {

    private static final String CODE_KEY_PREFIX = MemoryEmailCodeStore.CODE_KEY_PREFIX;
    private static final String COOLDOWN_KEY_PREFIX = MemoryEmailCodeStore.COOLDOWN_KEY_PREFIX;

    /**
     * KEYS[1]=codeKey ARGV[1]=expectedCode ARGV[2]=maxAttempts
     * 返回：1=ok 0=missing 2=mismatch 3=exhausted
     */
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return 0
            end
            local sep = string.find(raw, ':', 1, true)
            if not sep then
              redis.call('DEL', KEYS[1])
              return 0
            end
            local code = string.sub(raw, 1, sep - 1)
            local attempts = tonumber(string.sub(raw, sep + 1)) or 0
            local expected = ARGV[1]
            local maxAttempts = tonumber(ARGV[2])
            if code ~= expected then
              attempts = attempts + 1
              if attempts >= maxAttempts then
                redis.call('DEL', KEYS[1])
                return 3
              end
              local ttl = redis.call('PTTL', KEYS[1])
              local payload = code .. ':' .. attempts
              -- PTTL: >0 剩余毫秒；-1 无过期；0/-2 已过期或即将过期——禁止 SET 成永久键
              if ttl > 0 then
                redis.call('SET', KEYS[1], payload, 'PX', ttl)
                return 2
              end
              if ttl == -1 then
                redis.call('SET', KEYS[1], payload)
                return 2
              end
              redis.call('DEL', KEYS[1])
              return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    /**
     * 原子比较删除：仅当值前缀为 {@code code:} 时 DEL。
     * KEYS[1]=codeKey ARGV[1]=code  返回 1=deleted 0=kept/missing
     */
    private static final DefaultRedisScript<Long> DELETE_IF_MATCHES_SCRIPT = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return 0
            end
            local expected = ARGV[1] .. ':'
            if string.sub(raw, 1, string.len(expected)) == expected then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisEmailCodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(EmailPurpose purpose, String email, String code, Duration ttl) {
        redis.opsForValue().set(codeKey(purpose, email), code + ":0", ttl);
    }

    @Override
    public EmailCodeVerifyResult verifyAndConsume(EmailPurpose purpose, String email, String code, int maxAttempts) {
        Long raw = redis.execute(
                VERIFY_SCRIPT,
                Collections.singletonList(codeKey(purpose, email)),
                code,
                Integer.toString(maxAttempts));
        if (raw == null) {
            return EmailCodeVerifyResult.MISSING;
        }
        return switch (raw.intValue()) {
            case 1 -> EmailCodeVerifyResult.SUCCESS;
            case 2 -> EmailCodeVerifyResult.MISMATCH;
            case 3 -> EmailCodeVerifyResult.EXHAUSTED;
            default -> EmailCodeVerifyResult.MISSING;
        };
    }

    @Override
    public boolean tryAcquireCooldown(String email, Duration cooldown) {
        Boolean ok = redis.opsForValue().setIfAbsent(
                COOLDOWN_KEY_PREFIX + email, "1", cooldown);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void releaseCooldown(String email) {
        redis.delete(COOLDOWN_KEY_PREFIX + email);
    }

    @Override
    public void deleteIfCodeMatches(EmailPurpose purpose, String email, String code) {
        redis.execute(
                DELETE_IF_MATCHES_SCRIPT,
                Collections.singletonList(codeKey(purpose, email)),
                code);
    }

    @Override
    public OptionalLong ttlMillis(EmailPurpose purpose, String email) {
        Long ttl = redis.getExpire(codeKey(purpose, email), TimeUnit.MILLISECONDS);
        // Spring Data：-2 不存在，-1 无过期
        if (ttl == null || ttl < 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ttl);
    }

    private static String codeKey(EmailPurpose purpose, String email) {
        return CODE_KEY_PREFIX + purpose.keyToken() + ":" + email;
    }
}
