package com.lky.nexus.ai.config;

import com.lky.nexus.ai.model.AiModelRegistry;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 基础设施装配。
 *
 * <p>被 nexus-app 的组件扫描（{@code com.lky.nexus}）自动发现；
 * Phase 7 的 nexus-mcp-server 也能直接复用（它同样扫描 {@code com.lky.nexus}，或显式 import 本配置）。
 */
@Configuration
@EnableConfigurationProperties({AiModelProperties.class, RetrievalDefaults.class})
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
