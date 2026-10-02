package com.lky.nexus.app.infra;

import com.lky.nexus.app.security.QuotaChatUsageRecorder;
import com.lky.nexus.app.security.QuotaService;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.common.security.ChatUsageRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用级 bean 装配（跨层公共组件的装配统一放这里）。
 *
 * <p>{@link ObservabilityRegistry} 在 nexus-common 里是纯 Java 类——
 * common 是零 Spring 依赖的底座，装配是 app 层的事。
 */
@Configuration
public class AppConfig {

    @Bean
    public ObservabilityRegistry observabilityRegistry() {
        return new ObservabilityRegistry();
    }

    /**
     * token 记账端口（5.6）：nexus-ai 的 {@code UsageMeteringAdvisor} 只认接口，
     * 具体去向（每日配额、Redis 键）在 app 层。
     *
     * <p>本 bean 存在与否<b>不改变</b> {@code ChatClientFactory} 的构造：那边用
     * {@code ObjectProvider} 取，拿不到就退化成 {@code NOOP}。所以把 ai 层单独拿出来跑
     * （单测、Phase 7 的 mcp-server）不会因为缺这个 bean 而起不来。
     */
    @Bean
    public ChatUsageRecorder chatUsageRecorder(QuotaService quotaService) {
        return new QuotaChatUsageRecorder(quotaService);
    }
}