package com.lky.akh.ai.config;

import com.lky.akh.ai.model.AiModelRegistry;
import com.lky.akh.ai.model.ChatClientFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 基础设施装配。
 *
 * <p>被 akh-app 的组件扫描（{@code com.lky.akh}）自动发现；
 * Phase 7 的 akh-mcp-server 也能直接复用（它同样扫描 {@code com.lky.akh}，或显式 import 本配置）。
 */
@Configuration
@EnableConfigurationProperties(AiModelProperties.class)
public class AiConfiguration {

    @Bean
    public AiModelRegistry aiModelRegistry(AiModelProperties properties) {
        return new AiModelRegistry(properties);
    }

    @Bean
    public ChatClientFactory chatClientFactory(AiModelProperties properties, AiModelRegistry registry) {
        return new ChatClientFactory(properties, registry);
    }
}
