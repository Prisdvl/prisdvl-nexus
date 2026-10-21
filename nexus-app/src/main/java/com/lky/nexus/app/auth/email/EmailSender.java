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

    /**
     * 发送一条业务通知（不是验证码）：任务完成、导入失败这类"做完叫我一声"的消息。
     *
     * <p>与 {@link #sendCode} 分开而不是塞进同一个方法：验证码是<b>安全凭证</b>（不能进日志、
     * 有时效、要防爆破计数），通知是<b>普通文本</b>（可以进日志、没有时效、失败只记一条状态）。
     * 混在一起会逼每个实现去区分，迟早在通知路径上打出验证码。
     *
     * @param email 收件地址（已 normalize）
     * @param title 标题（纯文本，调用方负责本地化）
     * @param body  正文（纯文本）
     */
    void sendNotice(String email, String title, String body);
}
