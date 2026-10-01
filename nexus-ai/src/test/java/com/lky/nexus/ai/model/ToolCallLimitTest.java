package com.lky.nexus.ai.model;

import com.lky.nexus.ai.config.AiModelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具循环上限：把"框架内部默认值"变成"看得见、调得动、有测试"的配置（Phase 5.2 / P1-11）。
 *
 * <p><b>为什么这个测试非有不可</b>：本项目的模型由 {@code AiModelRegistry} 手工构建
 * （关闭了 Spring AI 的模型自动配置），而 Spring AI 提供的
 * {@code spring.ai.tools.limits.*} 只对"自动配置出来的模型"生效。
 * 也就是说，把上限写进那两个 key 是个<b>假旋钮</b>——改了没反应，
 * 却完美地让人以为已经配好了（这次就差点这么干，见 decisions.md B.43）。
 * 唯一能证明"上限真的生效"的方式：把上限调到 1，然后真的发起第 2 次工具调用。
 *
 * <p>这里不调模型、不发网络请求：{@code ToolCallingManager.executeToolCalls} 做的事就是
 * "拿一轮模型输出里的工具调用去执行"，上限判定正发生在这条路径上
 * （判定在读工具定义、执行工具之前，所以撞上限时那个工具一次都不会真的跑）。
 */
@DisplayName("工具循环上限：ai.tools.* 真的会限制工具调用次数")
class ToolCallLimitTest {

    @Test
    @DisplayName("单工具上限调到 1：同一轮里第 2 次调用该工具就抛异常，异常带上工具名与上限")
    void perToolLimitIsEnforcedFromConfiguration() {
        ToolCallingManager manager = registryWithLimits(1, 150).toolCallingManager();

        assertThatThrownBy(() -> manager.executeToolCalls(promptWithKnowledgeSearchTool(),
                responseCalling("knowledge_search", 2)))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .satisfies(thrown -> {
                    ToolCallLimitExceededException limit = (ToolCallLimitExceededException) thrown;
                    assertThat(limit.getToolName()).isEqualTo("knowledge_search");
                    assertThat(limit.getLimit()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("合计上限调到 1：第 2 次调用（哪怕换工具）也抛异常，且 toolName 为空")
    void totalLimitIsEnforcedAcrossTools() {
        ToolCallingManager manager = registryWithLimits(40, 1).toolCallingManager();

        // 单工具上限给了 40，所以这里触发的一定是"合计"那条上限
        ChatResponse twice = responseCalling("knowledge_search", 2);

        assertThatThrownBy(() -> manager.executeToolCalls(promptWithKnowledgeSearchTool(), twice))
                .isInstanceOf(ToolCallLimitExceededException.class)
                .satisfies(thrown -> {
                    ToolCallLimitExceededException limit = (ToolCallLimitExceededException) thrown;
                    // 合计上限的 toolName 是 null —— FailureClassifier 靠这一点区分撞的是哪个上限
                    assertThat(limit.getToolName()).isNull();
                    assertThat(limit.getLimit()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("额度够用时正常返回（对照：配置不是把所有调用都拦下来）")
    void callsWithinLimitPassThrough() {
        ToolCallingManager manager = registryWithLimits(1, 150).toolCallingManager();

        // 只调用一次，单工具上限 1 → 不该抛
        assertThat(manager.executeToolCalls(promptWithKnowledgeSearchTool(), responseCalling("knowledge_search", 1)))
                .isNotNull();
    }

    @Test
    @DisplayName("配置里的默认值必须与框架默认值一致（框架改了默认值时这条会先报出来）")
    void configuredDefaultsMatchFrameworkDefaults() {
        AiModelProperties.ToolLimits defaults = new AiModelProperties.ToolLimits();

        assertThat(defaults.getMaxCallsPerToolDefault())
                .isEqualTo(DefaultToolCallingManager.DEFAULT_MAX_CALLS_PER_TOOL);
        assertThat(defaults.getMaxTotalToolCalls())
                .isEqualTo(DefaultToolCallingManager.DEFAULT_MAX_TOTAL_TOOL_CALLS);
        // application.yml 里写明"这就是框架默认值 40 / 150"——框架一旦改默认值，
        // 那句注释就成了假话，所以把数字也钉在这里
        assertThat(defaults.getMaxCallsPerToolDefault()).isEqualTo(40);
        assertThat(defaults.getMaxTotalToolCalls()).isEqualTo(150);
    }

    // ------------------------------------------------------------------

    private AiModelRegistry registryWithLimits(int maxCallsPerTool, int maxTotalToolCalls) {
        AiModelProperties properties = new AiModelProperties();
        AiModelProperties.ToolLimits limits = properties.getTools();
        limits.setMaxCallsPerToolDefault(maxCallsPerTool);
        limits.setMaxTotalToolCalls(maxTotalToolCalls);
        return new AiModelRegistry(properties);
    }

    /** 一轮模型输出：同一个工具被调用 {@code times} 次。 */
    private ChatResponse responseCalling(String toolName, int times) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            calls.add(new AssistantMessage.ToolCall("call-" + i, "function", toolName, "{}"));
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(calls).build())))
                .build();
    }

    /**
     * 带上一个可用工具：真实链路上工具由 AgentToolFactory 装配，这里只要一个名字与
     * 被调用的工具一致的回调（名字对不上时框架会先报"找不到工具"，撞不到上限那条路径）。
     */
    private Prompt promptWithKnowledgeSearchTool() {
        ToolCallback knowledgeSearch = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("knowledge_search")
                        .description("测试替身：不做任何检索")
                        .inputSchema("""
                                {"type":"object","properties":{"q":{"type":"string"}}}
                                """)
                        .build();
            }

            @Override
            public String call(String input) {
                return "ok";
            }
        };
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(knowledgeSearch)
                .build();
        return new Prompt(List.of(new UserMessage("检索一下缓存击穿")), options);
    }
}
