package com.lky.nexus.app.auth.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 邮箱验证码基础设施装配。
 *
 * <p>存储与 {@link com.lky.nexus.app.security.SecurityStore} 同一开关
 * {@code nexus.security.redis-enabled}：true → Redis（Lua 原子消费），
 * false / 缺省 → 内存（test / standalone）。
 *
 * <p>发信：{@code console} 仅在 {@code local}/{@code standalone}/{@code test} 装配，
 * 避免生产默认把明文验证码打进日志；其它环境默认 {@link UnconfiguredEmailSender}（失败关闭）。
 */
@Configuration
@EnableConfigurationProperties(NexusEmailProperties.class)
public class EmailCodeConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "nexus.security", name = "redis-enabled", havingValue = "true")
    public EmailCodeStore redisEmailCodeStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisEmailCodeStore(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nexus.security", name = "redis-enabled",
            havingValue = "false", matchIfMissing = true)
    public EmailCodeStore memoryEmailCodeStore() {
        return new MemoryEmailCodeStore();
    }

    /**
     * 开发 / 测试控制台发信：必须同时满足 profile + {@code nexus.email.sender=console}。
     * 生产即使误设 sender=console，因无这些 profile 也不会装配。
     */
    @Bean
    @Profile({"local", "standalone", "test"})
    @ConditionalOnProperty(prefix = "nexus.email", name = "sender", havingValue = "console")
    public EmailSender consoleEmailSender() {
        return new ConsoleEmailSender();
    }

    /**
     * 真实 SMTP：任意 profile 可装配；缺 host/from 时 {@link SmtpEmailSender} 构造失败。
     * 生产未配 SMTP 时请保持 {@code sender=unconfigured}，勿指望自动降级为 console。
     */
    @Bean
    @ConditionalOnProperty(prefix = "nexus.email", name = "sender", havingValue = "smtp")
    public EmailSender smtpEmailSender(NexusEmailProperties properties) {
        return new SmtpEmailSender(properties.getSmtp());
    }

    /**
     * Resend HTTP API：任意 profile 可装配；缺 api-key/from 时 {@link ResendEmailSender} 构造失败。
     * 未配置时保持 {@code sender=unconfigured}，不会自动降级为 console。
     */
    @Bean
    @ConditionalOnProperty(prefix = "nexus.email", name = "sender", havingValue = "resend")
    public EmailSender resendEmailSender(NexusEmailProperties properties) {
        return new ResendEmailSender(properties.getResend());
    }

    /**
     * 默认 / 显式 {@code unconfigured}：不发信、不打码。
     */
    @Bean
    @ConditionalOnProperty(prefix = "nexus.email", name = "sender",
            havingValue = "unconfigured", matchIfMissing = true)
    public EmailSender unconfiguredEmailSender() {
        return new UnconfiguredEmailSender();
    }

    /**
     * 兜底：例如生产误配 {@code sender=console}（被 Profile 挡掉）时仍要有 bean，
     * 且必须失败关闭而不是静默无发送器导致启动失败或误用别的实现。
     */
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    public EmailSender fallbackUnconfiguredEmailSender() {
        return new UnconfiguredEmailSender();
    }
}
