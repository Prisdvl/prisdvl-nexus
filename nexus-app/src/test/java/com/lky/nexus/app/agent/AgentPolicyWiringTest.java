package com.lky.nexus.app.agent;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.AnswerPolicy;
import com.lky.nexus.ai.model.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>策略块真的到达模型了吗</b>——把"提示词组装"钉在模型边界上（Phase 4.4）。
 *
 * <p>为什么需要这一条：`AgentPromptsTest` 证明的是"<b>函数</b>拼得对"，
 * 而"<b>跑 Agent 的那条链路</b>有没有调用它"是另一件事。两者之间只隔着一行
 * （`AgentRuntime.configure` 里的 `request.system(...)`），但这一行恰恰是 4.4 的全部改动所在：
 * 若它没接上，模型收到的仍是用户提示词、没有策略块——表现为"新用户提问还是被拒"，
 * 而所有单测都是绿的。
 *
 * <p>拿一个桩 {@code ChatModel} 顶替真模型，捕获发出去的那个 {@link Prompt}：
 * 断言的对象因此不是"我们的函数返回了什么"，而是"模型到底看见了什么"。
 * 这条测试<b>不需要 API key</b>，能在任何环境下跑——这正是它存在的意义
 * （真机模型腿见 decisions.md B.41 第 11 条：那条在没 key 的环境里跑不了）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("回答策略：从 AgentDefinition 到模型收到的系统提示")
class AgentPolicyWiringTest {

    private static final String USER_PROMPT = "你是一个笔记助手，回答要简短。";

    @MockitoBean
    private ChatClientFactory chatClientFactory;

    @Autowired
    private AgentRuntime agentRuntime;

    /** 每次调用捕获到的 Prompt（本轮只跑一次，用列表是为了在失败信息里能看到实际内容）。 */
    private final List<Prompt> captured = new ArrayList<>();

    @BeforeEach
    void stubModel() {
        captured.clear();
        ChatModel stub = mock(ChatModel.class);
        // ChatClient 会把"请求级选项"与模型默认选项合并，默认选项为 null 会 NPE——
        // 这是桩模型必须补上的一格（真模型由 AiModelRegistry 构建，天然有值）
        when(stub.getOptions()).thenReturn(ChatOptions.builder().build());
        when(stub.call(any(Prompt.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))));
        });
        when(chatClientFactory.get(anyString())).thenReturn(ChatClient.builder(stub).build());
    }

    @Test
    @DisplayName("KB_PREFERRED：模型收到的是「用户提示词 + 策略块」，且策略块只追加一次")
    void preferredPolicyReachesTheModel() {
        agentRuntime.ask(1L, definition(AnswerPolicy.KB_PREFERRED), "缓存击穿怎么解决");

        String system = systemText();
        // 用户提示词原样保留并排在前面：策略块是**追加**的，不是替换掉用户那段
        assertThat(system).startsWith(USER_PROMPT);
        assertThat(system).contains("回答策略（KB_PREFERRED）");
        // 反归属：这里断言的是**策略块自己那一份**（"绝不把自己的知识说成是笔记里的内容"）。
        // 默认提示词里的另一份措辞（"绝不要把你自己的知识说成是用户笔记里的内容"）**不在**本断言内——
        // 用户写了自定义提示词时，它本来就不在链路里；能兜住任何提示词的只有策略块这一份
        assertThat(system).contains("绝不把自己的知识说成是笔记里的内容");
        // 只追加一次：两次会让模型同时看到"可以用自身知识"与"不可以用自身知识"
        assertThat(countOccurrences(system, "回答策略（")).isEqualTo(1);
    }

    @Test
    @DisplayName("KB_ONLY：同一份用户提示词，模型收到的是另一套策略（旋钮真的通到模型）")
    void kbOnlyPolicyReachesTheModel() {
        agentRuntime.ask(1L, definition(AnswerPolicy.KB_ONLY), "缓存击穿怎么解决");

        String system = systemText();
        // 用户提示词两边一字不差——变的只有策略块，这正是"策略与用户提示词分离"的意思
        assertThat(system).startsWith(USER_PROMPT);
        assertThat(system).contains("回答策略（KB_ONLY）");
        assertThat(system).doesNotContain("回答策略（KB_PREFERRED）");
        // 两套策略的区别：这一套禁止用自身知识作答（"不许答"与"可以答但要注明"）
        assertThat(system).contains("不要用自己的知识作答");
        assertThat(countOccurrences(system, "回答策略（")).isEqualTo(1);
    }

    @Test
    @DisplayName("默认提示词（用户没改过）走的是同一条接线：默认规则 + 策略块都在")
    void defaultPromptPathComposesToo() {
        // 这条钉的是"内置默认 Agent"那条路：它的 Definition.systemPrompt 就是 AGENT_SYSTEM，
        // 而 AGENT_SYSTEM 本身不含任何策略句——策略仍然只能来自追加的块
        AgentDefinition definition = new AgentDefinition(1L, "builtin.default", "默认助手",
                AgentPrompts.AGENT_SYSTEM, "chat.agent", null, List.of(), List.of(),
                AnswerPolicy.KB_PREFERRED, true);

        agentRuntime.ask(1L, definition, "缓存击穿怎么解决");

        String system = systemText();
        assertThat(system).contains("绝不要把你自己的知识说成是用户笔记里的内容");
        assertThat(system).contains("回答策略（KB_PREFERRED）");
        // 默认提示词自己**不含**回落规则：那两句只在策略块里（"策略只有一处"在模型边界上也成立）
        assertThat(countOccurrences(system, "不要用你自己的既有知识")).isZero();
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String systemText() {
        assertThat(captured).as("模型应当被调用恰好一次").hasSize(1);
        return captured.get(0).getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.SYSTEM)
                .map(message -> ((SystemMessage) message).getText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("发给模型的 Prompt 里没有系统消息：" + captured.get(0)));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** 只驱动"装提示词"这一条链路，其余字段取最小合法值（工具与检索范围不参与本类断言）。 */
    private static AgentDefinition definition(AnswerPolicy policy) {
        return new AgentDefinition(1L, "policy-wiring", "策略接线", USER_PROMPT,
                "chat.agent", null, List.of(), List.of(), policy, false);
    }
}
