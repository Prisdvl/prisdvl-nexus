package com.lky.nexus.app.auth.email;

import java.time.Duration;

/**
 * 邮件发送端口：业务只依赖本接口，不直接碰 SMTP / 云厂商 SDK。
 *
 * <p>实现可切换：开发期 {@link ConsoleEmailSender}；
 * 生产期 {@link SmtpEmailSender} / {@link ResendEmailSender}；
 * 未配置时 {@link UnconfiguredEmailSender}（失败关闭）。
 */
public interface EmailSender {

    /**
     * 发送验证码邮件。
     *
     * @param email   已 normalize 的邮箱
     * @param code    明文验证码（仅出现在邮件内容里，不落库）
     * @param purpose 用途（决定文案模板）
     * @param expire  有效期（写入邮件提示）
     */
    void sendCode(String email, String code, EmailPurpose purpose, Duration expire);
}
