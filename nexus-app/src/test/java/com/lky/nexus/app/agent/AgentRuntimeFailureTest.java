package com.lky.nexus.app.agent;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.AnswerPolicy;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.agent.tool.AgentToolFactory;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentRuntime#ask} 是<b>四个非流式入口</b>的唯一收口：{@code POST /api/agent/ask}、
 * Agent 试跑、评测集执行、MCP 的 {@code agent_ask}。这个测试钉的是它"失败时吐出什么"。
 *
 * <p><b>为什么值得单独测</b>：这个缺陷不是推演出来的，是在真机栈上用一把无效 Key 跑出来的——
 * 同一个故障，流式对话回 {@code 4001}「模型调用失败，请稍后重试」，而这四个入口一起漏到兜底处理器，
 * 变成 {@code 500 / 9999}「系统内部错误（requestId=…）」。用户看到 500 只会认为产品坏了；
 * 运维收到一条 ERROR 告警，而真相是上游 401。这四段路此前<b>一条测试都没有</b>。
 *
 * <p>这里不连数据库、不发网络请求：{@code ask} 只认值对象（构造一个 {@link AgentDefinition} 即可），
 * 模型侧用一个"一被调用就抛"的 {@link ChatModel} 替身，因此测的是收口本身，
 * 而不是某个供应商 SDK 的行为（这也是 {@code AgentRuntime} 刻意不碰 Entity 换来的好处）。
 */
@DisplayName("AgentRuntime.ask：模型侧失败要翻译成如实的业务异常，不能漏成 9999")
class AgentRuntimeFailureTest {

    @Test
    @DisplayName("上游拒绝密钥 → 4007 且不可重试（修复前：500「系统内部错误」，与流式入口不一致）")
    void credentialRejectionBecomesAuthFailed() {
        // 类名对齐真机栈上的 com.openai.errors.UnauthorizedException——
        // FailureClassifier 按类名识别这一类（它必须能与具体 SDK 解耦，理由见 B.43）
        AgentRuntime runtime = runtimeFailingWith(new UnauthorizedException("401: Invalid API key."));

        assertThatThrownBy(() -> runtime.ask(1L, definition(), "你好"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED.getCode());
                    assertThat(ex.getMessage()).isEqualTo(FailureClassifier.MODEL_AUTH_FAILED_MESSAGE);
                });

        // 不可重试是这条判定存在的全部意义：密钥错了，重试一万次都是同一个 401
        assertThat(FailureClassifier.classify(new UnauthorizedException("401")).retryable()).isFalse();
    }

    @Test
    @DisplayName("未知模型故障 → 4001 与那句固定文案（不是 9999）")
    void unknownFailureBecomesModelUnavailable() {
        AgentRuntime runtime = runtimeFailingWith(new IllegalStateException("upstream boom"));

        assertThatThrownBy(() -> runtime.ask(1L, definition(), "你好"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE.getCode());
                    assertThat(ex.getMessage()).isEqualTo(FailureClassifier.MODEL_FAILED_MESSAGE);
                    // 兜底处理器那套"只给 requestId、把堆栈留给日志"的文案不该出现在这里
                    assertThat(ex.getMessage()).doesNotContain("系统内部错误");
                });
    }

    @Test
    @DisplayName("已经是业务异常的（4006 未配置模型）原样透出，不被改写成 4001")
    void businessExceptionIsNotRewritten() {
        AgentRuntime runtime = runtimeFailingWith(
                new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED, "模型 chat.agent 未注册"));

        assertThatThrownBy(() -> runtime.ask(1L, definition(), "你好"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
                    assertThat(ex.getMessage()).isEqualTo("模型 chat.agent 未注册");
                });
    }

    // ------------------------------------------------------------------

    /** 真机栈上抛的就是这个名字（{@code com.openai.errors.UnauthorizedException}），这里只借它的类名。 */
    static class UnauthorizedException extends RuntimeException {
        UnauthorizedException(String message) {
            super(message);
        }
    }

    private AgentRuntime runtimeFailingWith(RuntimeException error) {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw error;
            }
        };

        ChatClientFactory factory = new ChatClientFactory(new AiModelProperties(), null) {
            @Override
            public ChatClient get(String role) {
                return ChatClient.builder(failing).build();
            }
        };

        // 工具装配与本次要验的事无关（失败发生在模型调用上），换成空实现，
        // 免得测试还得拉起 ToolRegistry 与观测注册表
        AgentToolFactory noTools = new AgentToolFactory(null, null) {
            @Override
            public ChatClient.ChatClientRequestSpec applyTo(ChatClient.ChatClientRequestSpec request,
                                                            Long userId,
                                                            AgentDefinition definition) {
                return request;
            }
        };

        // agentService 只在 askWithDefault 里用到，ask 用不上
        return new AgentRuntime(factory, noTools, null);
    }

    private AgentDefinition definition() {
        return new AgentDefinition(1L, "builtin.default", "默认助手",
                "你是助手", "chat.agent", List.of(), List.of(), AnswerPolicy.DEFAULT, true);
    }
}
