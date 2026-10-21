package com.lky.nexus.app.auth.email;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/**
 * 真实 SMTP 发信：参数来自 {@code nexus.email.smtp.*}，不读 {@code spring.mail.*}。
 *
 * <p>异常向上抛出（经 {@link BusinessException}），绝不伪装成发送成功。
 * 日志只记收件人与用途，不记验证码明文。
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(NexusEmailProperties.Smtp smtp) {
        this(createMailSender(smtp), requireFrom(smtp));
    }

    /** 测试注入 mock {@link JavaMailSender}。 */
    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendCode(String email, String code, EmailPurpose purpose, Duration expire) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(subjectOf(purpose));
            helper.setText(bodyOf(code, purpose, expire), false);
            mailSender.send(message);
            log.info("验证码邮件已提交 SMTP to={} purpose={}", email, purpose.keyToken());
        } catch (MailException | jakarta.mail.MessagingException ex) {
            log.warn("验证码邮件发送失败 to={} purpose={}", email, purpose.keyToken());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试", ex);
        }
    }

    @Override
    public void sendNotice(String email, String title, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(title);
            helper.setText(body, false);
            mailSender.send(message);
            log.info("通知邮件已提交 SMTP to={}", email);
        } catch (MailException | jakarta.mail.MessagingException ex) {
            log.warn("通知邮件发送失败 to={}", email);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "通知邮件发送失败", ex);
        }
    }
    private static JavaMailSender createMailSender(NexusEmailProperties.Smtp smtp) {
        if (smtp == null || !StringUtils.hasText(smtp.getHost())) {
            throw new IllegalStateException(
                    "nexus.email.sender=smtp 时必须配置 nexus.email.smtp.host");
        }
        requireFrom(smtp);

        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(smtp.getHost().trim());
        impl.setPort(smtp.getPort());
        if (StringUtils.hasText(smtp.getUsername())) {
            impl.setUsername(smtp.getUsername());
        }
        if (StringUtils.hasText(smtp.getPassword())) {
            impl.setPassword(smtp.getPassword());
        }
        impl.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = impl.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", StringUtils.hasText(smtp.getUsername()));
        props.put("mail.smtp.starttls.enable", Boolean.toString(smtp.isStartTls()));
        props.put("mail.smtp.starttls.required", Boolean.toString(smtp.isStartTls()));
        return impl;
    }

    private static String requireFrom(NexusEmailProperties.Smtp smtp) {
        if (smtp == null || !StringUtils.hasText(smtp.getFrom())) {
            throw new IllegalStateException(
                    "nexus.email.sender=smtp 时必须配置 nexus.email.smtp.from");
        }
        return smtp.getFrom().trim();
    }

    static String subjectOf(EmailPurpose purpose) {
        return switch (purpose) {
            case VERIFY_EMAIL -> "【Nexus】邮箱验证码";
            case REGISTER -> "【Nexus】注册验证码";
            case RESET_PASSWORD -> "【Nexus】密码重置验证码";
        };
    }

    static String bodyOf(String code, EmailPurpose purpose, Duration expire) {
        String action = switch (purpose) {
            case VERIFY_EMAIL -> "验证当前绑定邮箱";
            case REGISTER -> "完成注册";
            case RESET_PASSWORD -> "重置密码";
        };
        long minutes = Math.max(1, expire.toMinutes());
        return "您正在进行" + action + "操作。\n\n"
                + "验证码：" + code + "\n\n"
                + "有效期 " + minutes + " 分钟，请勿泄露给他人。\n"
                + "如非本人操作，请忽略本邮件。";
    }
}
