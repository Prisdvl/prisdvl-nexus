package com.lky.akh.ai.model;

import com.lky.akh.ai.config.AiModelProperties;
import com.lky.akh.common.api.ErrorCode;
import com.lky.akh.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型注册表与角色工厂测试（纯单元测试，不启动 Spring，也不发起任何网络请求）。
 *
 * <p>验证 ADR-003 的核心承诺：<b>改配置就能换模型，业务代码只认逻辑角色</b>。
 */
class AiModelRegistryTest {

    private static final String DUMMY_KEY = "sk-test-dummy-key-not-a-real-credential";

    @Test
    @DisplayName("按配置构建模型：注册表能列出 provider:model，角色能取到 ChatClient")
    void buildsModelsForConfiguredProviders() {
        AiModelProperties properties = propertiesWith(Map.of(
                AiModelProperties.Role.CHAT_DEFAULT, "deepseek:deepseek-chat",
                AiModelProperties.Role.CHAT_JUDGE, "deepseek:deepseek-chat"));

        AiModelRegistry registry = new AiModelRegistry(properties);
        ChatClientFactory factory = new ChatClientFactory(properties, registry);

        assertThat(registry.registeredChatModels()).containsExactly("deepseek:deepseek-chat");

        ChatClient defaultClient = factory.get(AiModelProperties.Role.CHAT_DEFAULT);
        ChatClient judgeClient = factory.get(AiModelProperties.Role.CHAT_JUDGE);
        assertThat(defaultClient).isNotNull();
        assertThat(judgeClient).isNotNull();
        // 相同模型引用共享同一个 ChatClient 实例（惰性缓存）
        assertThat(factory.get(AiModelProperties.Role.CHAT_DEFAULT)).isSameAs(defaultClient);
    }

    @Test
    @DisplayName("换供应商只改配置：同一个角色名换成 ollama 后解析到不同模型")
    void switchingProviderIsConfigurationOnly() {
        AiModelProperties properties = new AiModelProperties();
        properties.setProviders(List.of(
                provider("deepseek", AiModelProperties.ProviderType.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com", DUMMY_KEY, "deepseek-chat"),
                provider("ollama", AiModelProperties.ProviderType.OLLAMA,
                        "http://localhost:11434", null, "qwen3:7b")));
        // 同一个逻辑角色，先指向云端，再指向本地 —— 业务代码不需要任何改动
        properties.setRoles(Map.of(AiModelProperties.Role.CHAT_DEFAULT, "ollama:qwen3:7b"));

        AiModelRegistry registry = new AiModelRegistry(properties);
        assertThat(registry.registeredChatModels())
                .containsExactlyInAnyOrder("deepseek:deepseek-chat", "ollama:qwen3:7b");

        ChatClientFactory factory = new ChatClientFactory(properties, registry);
        assertThat(factory.get(AiModelProperties.Role.CHAT_DEFAULT)).isNotNull();
    }

    @Test
    @DisplayName("OpenAI 兼容 provider 缺 API Key 时被跳过，用到该角色报 4006（而不是拿空 Key 去请求）")
    void providerWithoutApiKeyIsSkippedWithClearError() {
        AiModelProperties properties = new AiModelProperties();
        properties.setProviders(List.of(
                provider("deepseek", AiModelProperties.ProviderType.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com", "", "deepseek-chat")));
        properties.setRoles(Map.of(AiModelProperties.Role.CHAT_DEFAULT, "deepseek:deepseek-chat"));

        AiModelRegistry registry = new AiModelRegistry(properties);
        assertThat(registry.registeredChatModels()).isEmpty();

        ChatClientFactory factory = new ChatClientFactory(properties, registry);
        assertThatThrownBy(() -> factory.get(AiModelProperties.Role.CHAT_DEFAULT))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
    }

    @Test
    @DisplayName("未配置的角色报 4006，并在提示里列出已配置角色")
    void unknownRoleFailsWithActionableMessage() {
        AiModelProperties properties = propertiesWith(
                Map.of(AiModelProperties.Role.CHAT_DEFAULT, "deepseek:deepseek-chat"));
        ChatClientFactory factory = new ChatClientFactory(properties, new AiModelRegistry(properties));

        assertThatThrownBy(() -> factory.get("chat.not-configured"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("chat.not-configured")
                .hasMessageContaining(AiModelProperties.Role.CHAT_DEFAULT);
    }

    @Test
    @DisplayName("嵌入模型不会被注册为对话模型（能力标签决定归属）")
    void embeddingOnlyModelIsNotRegisteredAsChatModel() {
        AiModelProperties properties = new AiModelProperties();
        AiModelProperties.Provider ollama = new AiModelProperties.Provider();
        ollama.setName("ollama");
        ollama.setType(AiModelProperties.ProviderType.OLLAMA);
        ollama.setBaseUrl("http://localhost:11434");
        AiModelProperties.ModelEntry embeddingEntry = new AiModelProperties.ModelEntry();
        embeddingEntry.setName("bge-m3");
        embeddingEntry.setCapabilities(List.of(AiModelProperties.ModelCapability.EMBEDDING));
        ollama.setModels(List.of(embeddingEntry));
        properties.setProviders(List.of(ollama));

        AiModelRegistry registry = new AiModelRegistry(properties);
        assertThat(registry.registeredChatModels()).isEmpty();
    }

    // ------------------------------------------------------------------

    private AiModelProperties propertiesWith(Map<String, String> roles) {
        AiModelProperties properties = new AiModelProperties();
        properties.setProviders(List.of(
                provider("deepseek", AiModelProperties.ProviderType.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com", DUMMY_KEY, "deepseek-chat")));
        properties.setRoles(roles);
        return properties;
    }

    private AiModelProperties.Provider provider(String name,
                                                AiModelProperties.ProviderType type,
                                                String baseUrl,
                                                String apiKey,
                                                String modelName) {
        AiModelProperties.ModelEntry entry = new AiModelProperties.ModelEntry();
        entry.setName(modelName);
        entry.setCapabilities(List.of(AiModelProperties.ModelCapability.CHAT,
                AiModelProperties.ModelCapability.TOOL_CALLING));

        AiModelProperties.Provider provider = new AiModelProperties.Provider();
        provider.setName(name);
        provider.setType(type);
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setModels(List.of(entry));
        return provider;
    }
}
