package com.lky.nexus.app.auth.email;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SMTP 发信：成功路径与发送异常不得伪装成功。
 */
class SmtpEmailSenderTest {

    @Test
    @DisplayName("成功：调用 JavaMailSender.send，主题/正文含用途与有效期，不含额外凭据日志断言")
    void sendDelegatesToJavaMailSender() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@nexus.test");
        sender.sendCode("user@example.com", "123456", EmailPurpose.VERIFY_EMAIL, Duration.ofMinutes(5));

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(message.getSubject()).isEqualTo("【Nexus】邮箱验证码");
        assertThat(message.getContent().toString()).contains("123456");
        assertThat(message.getContent().toString()).contains("5 分钟");
        assertThat(message.getContent().toString()).contains("验证当前绑定邮箱");
    }

    @Test
    @DisplayName("SMTP 异常 → BusinessException INTERNAL_ERROR，不伪装成功")
    void sendFailureSurfacesAsBusinessException() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));

        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@nexus.test");

        assertThatThrownBy(() ->
                sender.sendCode("user@example.com", "123456", EmailPurpose.VERIFY_EMAIL, Duration.ofMinutes(5)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
                    assertThat(be.getCause()).isInstanceOf(MailSendException.class);
                });
    }

    @Test
    @DisplayName("sender=smtp 缺 host → 构造失败")
    void missingHostFailsFast() {
        NexusEmailProperties.Smtp smtp = new NexusEmailProperties.Smtp();
        smtp.setFrom("noreply@nexus.test");
        assertThatThrownBy(() -> new SmtpEmailSender(smtp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host");
    }

    @Test
    @DisplayName("中文模板按用途区分")
    void templatesDifferByPurpose() {
        assertThat(SmtpEmailSender.subjectOf(EmailPurpose.REGISTER)).contains("注册");
        assertThat(SmtpEmailSender.subjectOf(EmailPurpose.RESET_PASSWORD)).contains("密码重置");
        assertThat(SmtpEmailSender.bodyOf("999999", EmailPurpose.RESET_PASSWORD, Duration.ofMinutes(10)))
                .contains("重置密码")
                .contains("10 分钟");
    }
}
