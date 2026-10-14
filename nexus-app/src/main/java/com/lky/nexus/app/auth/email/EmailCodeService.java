package com.lky.nexus.app.auth.email;

import com.lky.nexus.app.security.RateLimiter;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 邮箱验证码领域服务：生成 → 存储 → 发送 → 校验消费。
 *
 * <p>已登录验邮由 {@code EmailVerificationService} 调用本类（用途 {@link EmailPurpose#VERIFY_EMAIL}）；
 * 忘记密码由 {@code PasswordResetService} 调用（用途 {@link EmailPurpose#RESET_PASSWORD}）。
 * 注册验邮后续阶段同样复用，勿另起一套存储。
 */
@Service
public class EmailCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailCodeStore store;
    private final EmailSender sender;
    private final RateLimiter rateLimiter;
    private final NexusEmailProperties properties;

    public EmailCodeService(EmailCodeStore store,
                            EmailSender sender,
                            RateLimiter rateLimiter,
                            NexusEmailProperties properties) {
        this.store = store;
        this.sender = sender;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * 按来源 IP 做验证码发送限流（与用途 / 账户是否存在无关）。
     *
     * <p>密码重置等防枚举入口应在查库前调用本方法，再决定是否 {@link #sendSkippingIpLimit}，
     * 避免「仅已验证邮箱才计入 IP 配额」造成 200/429 旁路。
     */
    public void checkIpSendLimit(String clientIp) {
        NexusEmailProperties.Code cfg = properties.getCode();
        String ip = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
        rateLimiter.checkEmailCodeSend(ip, cfg.getIpPerHour());
    }

    /**
     * 发送验证码：IP 限流 → 邮箱冷却 → 生成 → 落库 → 发信。
     *
     * <p>不返回明文验证码：避免未来 Controller 误把码写进 HTTP 响应。
     * 测试通过注入可记录的 {@link EmailSender} 断言发出的码。
     *
     * <p>IP 限流在冷却之前：避免限流失败却占住冷却。
     * 发信失败会原子删除本码并释放冷却，允许立即重试。
     *
     * @param email    原始邮箱（内部 normalize）
     * @param purpose  用途
     * @param clientIp 来源 IP（{@code getRemoteAddr()}），供 IP 维限流
     */
    public void send(String email, EmailPurpose purpose, String clientIp) {
        checkIpSendLimit(clientIp);
        sendSkippingIpLimit(email, purpose);
    }

    /**
     * 已在入口做过 {@link #checkIpSendLimit} 后的发送（冷却 → 生成 → 落库 → 发信）。
     *
     * <p>供密码重置等「先统一 IP 限流、再按账户决定是否发信」的路径使用，避免重复计次。
     */
    public void sendSkippingIpLimit(String email, EmailPurpose purpose) {
        String normalized = Emails.normalize(email);
        NexusEmailProperties.Code cfg = properties.getCode();

        if (!store.tryAcquireCooldown(normalized, cfg.getCooldown())) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "验证码发送过于频繁，请 " + formatCooldownHint(cfg.getCooldown()) + " 后再试");
        }

        String code = generateNumericCode(cfg.getLength());
        store.save(purpose, normalized, code, cfg.getExpire());
        try {
            sender.sendCode(normalized, code, purpose, cfg.getExpire());
        } catch (RuntimeException ex) {
            // 先删码再放冷却：冷却仍持有时其他请求无法 save，避免误删并发新码
            store.deleteIfCodeMatches(purpose, normalized, code);
            store.releaseCooldown(normalized);
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试", ex);
        }
    }

    /**
     * 校验并消费验证码；成功则键已删除（一次性）。
     */
    public void verify(String email, EmailPurpose purpose, String code) {
        String normalized = Emails.normalize(email);
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_INVALID);
        }
        String trimmed = code.trim();
        NexusEmailProperties.Code cfg = properties.getCode();
        EmailCodeVerifyResult result = store.verifyAndConsume(
                purpose, normalized, trimmed, cfg.getMaxAttempts());
        switch (result) {
            case SUCCESS -> {
                // ok
            }
            case EXHAUSTED -> throw new BusinessException(ErrorCode.EMAIL_CODE_TOO_MANY_ATTEMPTS);
            case MISSING, MISMATCH -> throw new BusinessException(ErrorCode.EMAIL_CODE_INVALID);
        }
    }

    /** 供测试断言 TTL；业务勿依赖。 */
    public OptionalTtl peekTtl(String email, EmailPurpose purpose) {
        return new OptionalTtl(store.ttlMillis(purpose, Emails.normalize(email)));
    }

    private static String generateNumericCode(int length) {
        if (length < 4 || length > 10) {
            throw new IllegalStateException("nexus.email.code.length 必须在 4..10，当前=" + length);
        }
        int bound = (int) Math.pow(10, length);
        int value = RANDOM.nextInt(bound);
        return String.format("%0" + length + "d", value);
    }

    private static String formatCooldownHint(Duration cooldown) {
        long seconds = Math.max(1, cooldown.toSeconds());
        if (seconds % 60 == 0) {
            return (seconds / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    /** 测试用 TTL 视图，避免把 OptionalLong 泄漏到业务签名。 */
    public record OptionalTtl(java.util.OptionalLong millis) {
        public boolean isPresent() {
            return millis.isPresent();
        }

        public long getAsLong() {
            return millis.getAsLong();
        }
    }
}
