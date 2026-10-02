package com.lky.nexus.ai.config;

import com.lky.nexus.ai.model.AiModelRegistry;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.common.security.ChatUsageRecorder;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * token 记账端口是可选依赖：它的实现（含 Redis 配额）在 nexus-app 层装配，
     * 而 nexus-ai 单独被引用时（纯 AI 层单测、Phase 7 的 mcp-server）不该被迫提供一个。
     * 因此用 {@link ObjectProvider} 取，缺省退化为 {@link ChatUsageRecorder#NOOP}。
     */
    @Bean
    public ChatClientFactory chatClientFactory(AiModelProperties properties, AiModelRegistry registry,
                                               ObjectProvider<ChatUsageRecorder> usageRecorder) {
        return new ChatClientFactory(properties, registry,
                usageRecorder.getIfAvailable(() -> ChatUsageRecorder.NOOP));
    }
}
