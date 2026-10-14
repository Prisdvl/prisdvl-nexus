package com.lky.nexus.app.auth.email;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * 邮箱验证码存储端口（与 {@link com.lky.nexus.app.security.SecurityStore} 分离）。
 *
 * <p>SecurityStore 只做「带 TTL 的计数器」，表达不了
 * {@code {code, attempts}} 的原子校验消费；本接口专管验证码生命周期。
 *
 * <p>实现：{@link MemoryEmailCodeStore}（test / standalone）、
 * {@link RedisEmailCodeStore}（local / 多实例，Lua 原子消费）。
 */
public interface EmailCodeStore {

    /**
     * 写入或覆盖验证码；attempts 归零，TTL 重新起算。
     */
    void save(EmailPurpose purpose, String email, String code, Duration ttl);

    /**
     * 原子校验并消费：成功则删除；失败则 attempts+1，达上限则删除。
     *
     * <p><b>禁止</b> GET → 校验 → DELETE 的非原子流程（并发下会双成功）。
     */
    EmailCodeVerifyResult verifyAndConsume(EmailPurpose purpose, String email, String code, int maxAttempts);

    /**
     * 尝试占用发送冷却：键不存在时写入并返回 true；已在冷却中返回 false。
     * Key：{@code nexus:auth:email-code-cooldown:{email}}
     */
    boolean tryAcquireCooldown(String email, Duration cooldown);

    /**
     * 释放发送冷却（发信失败回滚时调用，允许立即重试）。
     */
    void releaseCooldown(String email);

    /**
     * 仅当存储中的码仍等于 {@code code} 时删除（发信失败回滚，避免误删并发新码）。
     *
     * <p>实现必须原子：Redis 用 Lua；Memory 用 {@code ConcurrentHashMap#compute}。
     * 调用方约定（{@link EmailCodeService#send}）：在仍持有冷却时先调用本方法再
     * {@link #releaseCooldown}，因此正常路径下不会出现「读到旧码 → 他方覆盖 → 误删新码」。
     * Lua/compute 是第二道防线，防止将来其它调用方漏掉冷却约束。
     */
    void deleteIfCodeMatches(EmailPurpose purpose, String email, String code);

    /**
     * 剩余 TTL（毫秒）；不存在返回 empty。供测试断言。
     */
    OptionalLong ttlMillis(EmailPurpose purpose, String email);
}
