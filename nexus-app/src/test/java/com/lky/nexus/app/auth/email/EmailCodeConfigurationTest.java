package com.lky.nexus.app.auth.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发信 Bean 装配：生产默认失败关闭；开发 profile 才装配 ConsoleEmailSender。
 */
class EmailCodeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmailCodeConfiguration.class)
            .withPropertyValues("nexus.security.redis-enabled=false");

    @Test
    @DisplayName("无开发 profile：默认 UnconfiguredEmailSender，send 不打码")
    void defaultIsUnconfiguredWithoutDevProfile() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EmailSender.class);
            assertThat(ctx.getBean(EmailSender.class)).isInstanceOf(UnconfiguredEmailSender.class);
            assertThatThrownBy(() -> ctx.getBean(EmailSender.class)
                    .sendCode("a@b.com", "123456", EmailPurpose.REGISTER, java.time.Duration.ofMinutes(5)))
                    .isInstanceOf(com.lky.nexus.common.exception.BusinessException.class);
        });
    }

    @Test
    @DisplayName("生产误配 sender=console：Profile 挡掉后仍失败关闭")
    void consolePropertyWithoutDevProfileFallsBackToUnconfigured() {
        runner.withPropertyValues("nexus.email.sender=console").run(ctx -> {
            assertThat(ctx).hasSingleBean(EmailSender.class);
            assertThat(ctx.getBean(EmailSender.class)).isInstanceOf(UnconfiguredEmailSender.class);
        });
    }

    @Test
    @DisplayName("test profile + sender=console：装配 ConsoleEmailSender")
    void consoleEnabledOnTestProfile() {
        runner.withPropertyValues(
                "spring.profiles.active=test",
                "nexus.email.sender=console"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(EmailSender.class);
            assertThat(ctx.getBean(EmailSender.class)).isInstanceOf(ConsoleEmailSender.class);
        });
    }

    @Test
    @DisplayName("redis-enabled=false：MemoryEmailCodeStore（测试不依赖 Redis）")
    void memoryStoreWhenRedisDisabled() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EmailCodeStore.class);
            assertThat(ctx.getBean(EmailCodeStore.class)).isInstanceOf(MemoryEmailCodeStore.class);
        });
    }

    @Test
    @DisplayName("sender=smtp 且配置 host/from：装配 SmtpEmailSender")
    void smtpSenderWhenConfigured() {
        runner.withPropertyValues(
                "nexus.email.sender=smtp",
                "nexus.email.smtp.host=smtp.example.com",
                "nexus.email.smtp.from=noreply@example.com"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(EmailSender.class);
            assertThat(ctx.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
        });
    }

    @Test
    @DisplayName("sender=smtp 缺 host：上下文启动失败（不静默降级 console）")
    void smtpMissingHostFailsContext() {
        runner.withPropertyValues(
                "nexus.email.sender=smtp",
                "nexus.email.smtp.from=noreply@example.com"
        ).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("sender=resend 且配置 api-key/from：装配 ResendEmailSender")
    void resendSenderWhenConfigured() {
        runner.withPropertyValues(
                "nexus.email.sender=resend",
                "nexus.email.resend.api-key=re_test_fake",
                "nexus.email.resend.from=noreply@example.com"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(EmailSender.class);
            assertThat(ctx.getBean(EmailSender.class)).isInstanceOf(ResendEmailSender.class);
        });
    }

    @Test
    @DisplayName("sender=resend 缺 api-key：上下文启动失败（不静默降级 console）")
    void resendMissingApiKeyFailsContext() {
        runner.withPropertyValues(
                "nexus.email.sender=resend",
                "nexus.email.resend.from=noreply@example.com"
        ).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("默认仍为 unconfigured：新增 resend 不改变失败关闭边界")
    void defaultRemainsUnconfiguredAfterResendAdded() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(EmailSender.class)).isInstanceOf(UnconfiguredEmailSender.class);
            assertThat(ctx.getBeansOfType(EmailSender.class)).hasSize(1);
        });
    }
}
