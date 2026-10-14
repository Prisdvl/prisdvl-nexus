package com.lky.nexus.app.agent;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.AnswerPolicy;
import com.lky.nexus.ai.model.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
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
 * <b>温度这个旋钮真的通到模型了吗</b>——把 {@code agent.temperature} 钉在模型边界上（Phase 5.4）。
 *
 * <p>手法与 {@link AgentPolicyWiringTest} 完全一样，理由也一样：那一类测的是"<b>函数</b>算得对"，
 * 而"<b>跑 Agent 的链路</b>有没有把它交出去"是另一件事，中间只隔着一行
 * （{@code AgentRuntime.configure} 里的 {@code request.options(...)}）。本类专门盯那一行，
 * 因为 5.4 的 P1-8 要修的就是"字段配了、没人读"这种假旋钮（§六十七）。
 *
 * <p><b>桩模型带一个非空默认温度</b>（0.7），这不是随手取的数：
 * 它是本类里"<b>留空 = 跟随默认</b>"这条断言的唯一证据。若桩模型的默认温度是空的，
 * "没配"与"配成 null"看起来完全一样，那条断言就退化成同义反复。
 * 顺带它还证明合并<b>没有把别的字段冲掉</b>（断言里连 model 一起看）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("采样温度：从 AgentDefinition 到模型收到的请求选项")
class AgentTemperatureWiringTest {

    /** 桩模型自带的默认温度（相当于全局 {@code ai.defaults.temperature}）。 */
    private static final Double MODEL_DEFAULT_TEMPERATURE = 0.7;

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
        // 模型默认选项必须有值：合并的语义是"从模型默认 mutate() 出发、被请求级非空值逐字段覆盖"
        // （已核对字节码：DefaultChatClientUtils.toChatClientRequest 里
        //  ChatModel.getOptions().mutate().combineWith(requestSpec.getOptionsCustomizer())），
        // 所以这一格同时是"合并会保留其余字段"的探针
        when(stub.getOptions()).thenReturn(ChatOptions.builder()
                .model("stub-model")
                .temperature(MODEL_DEFAULT_TEMPERATURE)
                .build());
        when(stub.call(any(Prompt.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))));
        });
        when(chatClientFactory.get(anyString(), any())).thenReturn(ChatClient.builder(stub).build());
    }

    @Test
    @DisplayName("配了 0.2：模型收到的请求选项就是 0.2（不是「配了但没人读」）")
    void configuredTemperatureReachesTheModel() {
        agentRuntime.ask(1L, definition(0.2), "缓存击穿怎么解决");

        assertThat(temperatureSentToModel()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("配了 0：也是一个真的请求——这一条就是「0 与 NULL 不同义」的证据")
    void zeroIsARealRequestAndNotAnAbsent() {
        agentRuntime.ask(1L, definition(0.0), "缓存击穿怎么解决");

        // 0 与 0.7 不同，说明它确实盖住了默认值；而它又不是 null，说明"我要最确定的回答"
        // 这个意图被原样送了出去，没有被当成"没填"而丢掉
        assertThat(temperatureSentToModel()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("没配（null）：模型用自己的默认温度——「跟随全局」不是「跟随 0」")
    void absentTemperatureLeavesTheModelDefaultInPlace() {
        agentRuntime.ask(1L, definition(null), "缓存击穿怎么解决");

        // 这里绝不能是 0：把 null 落成 0 之后，用户在全局配置里调温度，
        // 所有"从没配过"的 Agent 会统统不动——而界面上它们写的是"服务端默认"
        assertThat(temperatureSentToModel())
                .as("null = 不设置请求级温度，让模型的默认值穿过去")
                .isEqualTo(MODEL_DEFAULT_TEMPERATURE);
    }

    @Test
    @DisplayName("合并不会冲掉别的东西：model 仍然是模型自己的（只覆盖温度这一个字段）")
    void mergingKeepsTheOtherModelOptions() {
        agentRuntime.ask(1L, definition(0.2), "缓存击穿怎么解决");

        assertThat(promptSentToModel().getOptions().getModel())
                .as("请求级选项是「覆盖」而不是「替换」——丢掉 model 就等于换了个模型")
                .isEqualTo("stub-model");
        // 提示词也照旧送达：温度是新加的一行，不该动到它前面那条组装
        assertThat(systemText()).startsWith(USER_PROMPT);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 模型这一轮收到的请求级温度。 */
    private Double temperatureSentToModel() {
        return promptSentToModel().getOptions().getTemperature();
    }

    private Prompt promptSentToModel() {
        assertThat(captured).as("模型应当被调用恰好一次").hasSize(1);
        return captured.get(0);
    }

    private String systemText() {
        return promptSentToModel().getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.SYSTEM)
                .map(message -> ((SystemMessage) message).getText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("发给模型的 Prompt 里没有系统消息：" + promptSentToModel()));
    }

    /** 只驱动"装配请求"这一条链路，其余字段取最小合法值。 */
    private static AgentDefinition definition(Double temperature) {
        return new AgentDefinition(1L, "temperature-wiring", "温度接线", USER_PROMPT,
                "chat.agent", temperature, List.of(), List.of(), AnswerPolicy.KB_PREFERRED, false);
    }
}
