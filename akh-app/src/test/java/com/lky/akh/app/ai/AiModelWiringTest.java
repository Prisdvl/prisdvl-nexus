package com.lky.akh.app.ai;

import com.lky.akh.ai.config.AiModelProperties;
import com.lky.akh.ai.model.AiModelRegistry;
import com.lky.akh.ai.model.ChatClientFactory;
import com.lky.akh.common.api.ErrorCode;
import com.lky.akh.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 1.2 验收测试：模型可替换层在<b>完整 Spring 上下文</b>中的装配。
 *
 * <p>与 akh-ai 里的纯单元测试互补：这里验证配置绑定（{@code ai.providers} / {@code ai.roles}）
 * 与"starter 自动配置确实被关掉"这两件只有真实上下文才能证明的事。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiModelWiringTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiModelRegistry aiModelRegistry;

    @Autowired
    private ChatClientFactory chatClientFactory;

    @Test
    @DisplayName("spring.ai.model.chat=none 生效：容器里没有任何 Spring AI 自动配置的 ChatModel bean")
    void springAiAutoConfigurationIsDisabled() {
        // 这是 ADR-003 的关键前提：模型全部由 AiModelRegistry 手工构建，不受 starter 单 bean 限制。
        // 若该断言失败，说明 starter 仍在自动配置，多 provider 方案会被它干扰。
        assertThat(applicationContext.getBeanNamesForType(ChatModel.class))
                .as("应当由 AiModelRegistry 管理模型，而不是交给 Spring AI 自动配置")
                .isEmpty();
    }

    @Test
    @DisplayName("配置绑定生效：注册表按 ai.providers 构建出模型")
    void registryIsBuiltFromConfiguration() {
        assertThat(aiModelRegistry.registeredChatModels()).contains("deepseek:deepseek-chat");
    }

    @Test
    @DisplayName("逻辑角色可解析：chat.default 能取到 ChatClient")
    void roleResolvesToChatClient() {
        assertThat(chatClientFactory.configuredRoles())
                .contains(AiModelProperties.Role.CHAT_DEFAULT,
                        AiModelProperties.Role.CHAT_AGENT,
                        AiModelProperties.Role.CHAT_JUDGE,
                        AiModelProperties.Role.EMBEDDING_DEFAULT);
        assertThat(chatClientFactory.get(AiModelProperties.Role.CHAT_AGENT)).isNotNull();
    }

    @Test
    @DisplayName("未配置角色报 4006，提示里带可操作信息")
    void unknownRoleProducesBusinessError() {
        assertThatThrownBy(() -> chatClientFactory.get("chat.does-not-exist"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
    }
}
