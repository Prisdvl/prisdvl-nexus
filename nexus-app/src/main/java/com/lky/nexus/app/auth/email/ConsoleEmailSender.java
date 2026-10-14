package com.lky.nexus.app.auth.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 开发 / 测试用发送器：不真正发信，只打结构化日志（含明文验证码）。
 *
 * <p>仅由 {@link EmailCodeConfiguration} 在 {@code local}/{@code standalone}/{@code test}
 * 且 {@code nexus.email.sender=console} 时装配。生产不会走到这里。
 */
public class ConsoleEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailSender.class);

    @Override
    public void sendCode(String email, String code, EmailPurpose purpose, Duration expire) {
        log.info("""
                [EMAIL]
                to: {}
                purpose: {}
                code: {}
                expire: {}
                """, email, purpose.keyToken(), code, expire);
    }
}
