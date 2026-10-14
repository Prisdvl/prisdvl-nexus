package com.lky.nexus.app.auth.email;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内验证码存储：{@code test} / {@code standalone} 默认实现。
 *
 * <p>并发安全靠 {@link ConcurrentHashMap#compute}：校验与消费在同一原子映射步骤内完成，
 * 等价于 Redis Lua / GETDEL 的「只能一个人成功」。
 */
public class MemoryEmailCodeStore implements EmailCodeStore {

    static final String CODE_KEY_PREFIX = "nexus:auth:email-code:";
    static final String COOLDOWN_KEY_PREFIX = "nexus:auth:email-code-cooldown:";

    private final ConcurrentHashMap<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();

    private record CodeEntry(String code, int attempts, long expiresAtMillis) {
        boolean expired(long now) {
            return expiresAtMillis <= now;
        }
    }

    @Override
    public void save(EmailPurpose purpose, String email, String code, Duration ttl) {
        long expires = System.currentTimeMillis() + ttl.toMillis();
        codes.put(codeKey(purpose, email), new CodeEntry(code, 0, expires));
    }

    @Override
    public EmailCodeVerifyResult verifyAndConsume(EmailPurpose purpose, String email, String code, int maxAttempts) {
        String key = codeKey(purpose, email);
        final EmailCodeVerifyResult[] result = {EmailCodeVerifyResult.MISSING};

        codes.compute(key, (ignored, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || existing.expired(now)) {
                result[0] = EmailCodeVerifyResult.MISSING;
                return null;
            }
            if (!existing.code().equals(code)) {
                int next = existing.attempts() + 1;
                if (next >= maxAttempts) {
                    result[0] = EmailCodeVerifyResult.EXHAUSTED;
                    return null;
                }
                result[0] = EmailCodeVerifyResult.MISMATCH;
                return new CodeEntry(existing.code(), next, existing.expiresAtMillis());
            }
            result[0] = EmailCodeVerifyResult.SUCCESS;
            return null; // 消费：删除
        });

        return result[0];
    }

    @Override
    public boolean tryAcquireCooldown(String email, Duration cooldown) {
        String key = COOLDOWN_KEY_PREFIX + email;
        long now = System.currentTimeMillis();
        long until = now + cooldown.toMillis();
        // 不能用「compute 返回值 == until」判断是否新占用：同一毫秒内两次调用
        // 的 until 相同，冷却中的第二次也会误判为成功（全量测试曾因此偶发失败）。
        boolean[] acquired = {false};
        cooldowns.compute(key, (ignored, existing) -> {
            if (existing != null && existing > now) {
                return existing; // 仍在冷却
            }
            acquired[0] = true;
            return until;
        });
        return acquired[0];
    }

    @Override
    public void releaseCooldown(String email) {
        cooldowns.remove(COOLDOWN_KEY_PREFIX + email);
    }

    @Override
    public void deleteIfCodeMatches(EmailPurpose purpose, String email, String code) {
        String key = codeKey(purpose, email);
        codes.compute(key, (ignored, existing) -> {
            if (existing != null && existing.code().equals(code)) {
                return null;
            }
            return existing;
        });
    }

    @Override
    public OptionalLong ttlMillis(EmailPurpose purpose, String email) {
        CodeEntry entry = codes.get(codeKey(purpose, email));
        if (entry == null) {
            return OptionalLong.empty();
        }
        long remain = entry.expiresAtMillis() - System.currentTimeMillis();
        if (remain <= 0) {
            codes.remove(codeKey(purpose, email), entry);
            return OptionalLong.empty();
        }
        return OptionalLong.of(remain);
    }

    static String codeKey(EmailPurpose purpose, String email) {
        return CODE_KEY_PREFIX + purpose.keyToken() + ":" + email;
    }
}
