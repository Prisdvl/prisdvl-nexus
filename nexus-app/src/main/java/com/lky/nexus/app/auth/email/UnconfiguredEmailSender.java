package com.lky.nexus.app.auth.email;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.time.Duration;

/**
 * 未配置真实发信通道时的失败关闭实现：不落日志、不吐验证码。
 *
 * <p>生产（无 {@code local}/{@code standalone}/{@code test} profile）默认装配本类，
 * 避免 {@link ConsoleEmailSender} 把明文验证码写进日志。
 */
public class UnconfiguredEmailSender implements EmailSender {

    @Override
    public void sendCode(String email, String code, EmailPurpose purpose, Duration expire) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "邮件发送未配置：请设置 nexus.email.sender（开发可用 console）");
    }
}
