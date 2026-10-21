package com.lky.nexus.app.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * 通知相关 bean 的装配。
 *
 * <p>开关与超时都从 {@code nexus.notify.webhook.*} 读（application.yml 里给了默认值，
 * standalone / test profile 打开 {@code allow-private-endpoints} 以便本地验证投递）。
 */
@Configuration
public class NotifyConfiguration {

    @Bean
    public WebhookSender webhookSender(
            Environment environment,
            @Value("${nexus.notify.webhook.timeout:5s}") Duration timeout) {
        // 容器里没有 com.fasterxml.jackson.databind.ObjectMapper（项目用的是 Jackson 3 的
        // tools.jackson），所以这里自己造一把：Webhook 体只有四个字段，不需要定制任何模块。
        // 用 Environment 读而不是 @Value("${...:false}") boolean —— 后者在配置缺失时行为依赖转换器，
        // 而这里"没写就等于不许内网"是安全默认值，必须显式。
        boolean allowPrivate = Boolean.parseBoolean(
                environment.getProperty("nexus.notify.webhook.allow-private-endpoints", "false"));
        return new WebhookSender(new ObjectMapper(), allowPrivate, timeout);
    }
}
