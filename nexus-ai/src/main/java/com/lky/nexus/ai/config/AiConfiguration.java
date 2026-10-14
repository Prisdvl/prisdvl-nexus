package com.lky.nexus.ai.config;

import com.lky.nexus.ai.model.AiModelRegistry;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.common.observe.ObservabilityRegistry;
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

    /**
     * 注册 {@link AiModelRegistry}：持有按 {@code ai.providers} 构建并缓存的多个 {@link ChatModel}。
     *
     * @param properties 模型配置（{@code ai.*}）
     * @return 构建好的模型注册表
     */
    @Bean
    public AiModelRegistry aiModelRegistry(AiModelProperties properties) {
        return new AiModelRegistry(properties);
    }

    /**
     * token 记账端口与观测注册表都是<b>可选</b>依赖：它们的装配在 nexus-app 层，
     * 而 nexus-ai 单独被引用时（纯 AI 层单测、Phase 7 的 mcp-server）不该被迫提供一个。
     * 因此用 {@link ObjectProvider} 取，缺省退化为 {@link ChatUsageRecorder#NOOP}
     * 与一个不带 Micrometer 的注册表（指标记录变成空操作）。
     *
     * <p>观测注册表由这里传给 {@code UsageMeteringAdvisor}，而 Advisor 挂在
     * {@code ChatClientFactory} 造的每一个 client 上——于是"模型调用次数 / Token 量 / 耗时"
     * 这三件事在六个调用点（Chat / ChatStream / Rag / RagStream / Agent / Reranker）
     * 自动全覆盖，不需要各处各加一行。
     */
    @Bean
    public ChatClientFactory chatClientFactory(AiModelProperties properties, AiModelRegistry registry,
                                               ObjectProvider<ChatUsageRecorder> usageRecorder,
                                               ObjectProvider<ObservabilityRegistry> observability,
                                               ObjectProvider<com.lky.nexus.ai.model.UserChatCredentialsProvider> credentialsProvider) {
        return new ChatClientFactory(properties, registry,
                usageRecorder.getIfAvailable(() -> ChatUsageRecorder.NOOP),
                observability.getIfAvailable(ObservabilityRegistry::new),
                credentialsProvider.getIfAvailable());
    }
}
