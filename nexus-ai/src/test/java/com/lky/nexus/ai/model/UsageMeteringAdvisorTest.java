package com.lky.nexus.ai.model;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.common.security.ChatUsageRecorder;
import com.lky.nexus.common.security.UserContextKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * token 记账（5.6）的行为：记什么、记几次、什么情况下拒记但要留痕。
 *
 * <p>这些用例都在守同一类风险——<b>记账是"错了也不报错"的功能</b>：
 * 漏记、少记、记重复，用户端一切正常，只有月底的账单会说话。
 * 所以每一条能想到的错法都要在这里钉住。
 *
 * <p>最后一组用例（{@code 装配}）不 mock Advisor 链，而是让真实的
 * {@code ChatClient} 跑一遍：{@code ChatClientFactory} 把记账顾问挂在
 * {@code defaultAdvisors} 上，而"默认顾问到底有没有生效"是<b>这次改动里最容易静默失效的一环</b>
 * ——挂错了地方（比如被请求级 {@code .advisors(...)} 挤掉）不会编译失败，也不会报错，
 * 只会永远记不到账。只有真跑一次能证明。
 */
@DisplayName("token 记账：Advisor 的记账口径")
class UsageMeteringAdvisorTest {

    // ------------------------------------------------------------------
    // 记账内容

    @Test
    @DisplayName("非流式：把 prompt/completion 记到上下文里的那个 userId 名下")
    void callRecordsUsageUnderContextUserId() {
        RecordingRecorder recorder = new RecordingRecorder();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = response(usage(12, 34), "deepseek-v4-flash");
        given(chain.nextCall(any())).willReturn(response);

        ChatClientResponse returned = new UsageMeteringAdvisor(recorder)
                .adviseCall(request(7L), chain);

        assertThat(returned).isSameAs(response);
        assertThat(recorder.records).containsExactly("user=7 prompt=12 completion=34");
        assertThat(recorder.unattributed).isEmpty();
    }

    @Test
    @DisplayName("★ 流式：多帧里只有末帧带 usage 时只记一次，且取最后一次的值（不是把各帧加起来）")
    void streamRecordsOnceWithTheLastFramesUsage() {
        RecordingRecorder recorder = new RecordingRecorder();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        given(chain.nextStream(any())).willReturn(Flux.just(
                response(new EmptyUsage(), "m"),      // 开头几帧没有用量
                response(usage(0, 0), "m"),           // 有的供应商给全 0
                response(usage(10, 20), "m"),         // 末帧的真实用量
                response(usage(10, 25), "m")));       // 修正帧（框架会发）

        new UsageMeteringAdvisor(recorder)
                .adviseStream(request(7L), chain)
                .collectList()
                .block();

        // 一帧一笔会把一次调用记成 4 笔；把各帧相加会记成 45。
        // 只有"末帧的值"是对的：35
        assertThat(recorder.records).containsExactly("user=7 prompt=10 completion=25");
    }

    @Test
    @DisplayName("供应商没回传用量（EmptyUsage）→ 一笔都不记，也不当作异常")
    void emptyUsageRecordsNothing() {
        RecordingRecorder recorder = new RecordingRecorder();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(response(new EmptyUsage(), "m"));

        new UsageMeteringAdvisor(recorder).adviseCall(request(7L), chain);

        assertThat(recorder.records).isEmpty();
        assertThat(recorder.unattributed).isEmpty();
    }

    // ------------------------------------------------------------------
    // 归属

    @Test
    @DisplayName("★ 上下文里没有 userId → 记 unattributed + 模型名，而不是静默丢掉")
    void missingUserIdIsCountedNotSilentlyDropped() {
        RecordingRecorder recorder = new RecordingRecorder();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(response(usage(5, 5), "deepseek-v4-flash"));

        new UsageMeteringAdvisor(recorder)
                .adviseCall(ChatClientRequest.builder().prompt(new Prompt("hi")).build(), chain);

        assertThat(recorder.records).isEmpty();
        // 关键在"不静默"：漏传 userId 是缺陷信号，必须能被看见，
        // 而且日志里要带模型名，才找得到是哪个入口漏的
        assertThat(recorder.unattributed).containsExactly("deepseek-v4-flash");
    }

    @Test
    @DisplayName("显式标注的系统调用 → 彻底跳过：既不记账，也不算「漏传」")
    void systemCallIsSkippedEntirely() {
        RecordingRecorder recorder = new RecordingRecorder();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        given(chain.nextCall(any())).willReturn(response(usage(5, 5), "m"));

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("hi"))
                .context(Map.of(UserContextKeys.SYSTEM_CALL, Boolean.TRUE))
                .build();

        new UsageMeteringAdvisor(recorder).adviseCall(request, chain);

        // 重排（LlmReranker）每次检索都会跑：它要是被记成 unattributed，
        // 那个告警计数就永远是满的，等于没有
        assertThat(recorder.records).isEmpty();
        assertThat(recorder.unattributed).isEmpty();
    }

    @Test
    @DisplayName("记账方抛异常 → 调用结果照常返回（记账绝不能把成功的模型调用变成失败）")
    void recorderFailureDoesNotBreakTheCall() {
        ChatUsageRecorder exploding = new ChatUsageRecorder() {
            @Override
            public void record(Long userId, int promptTokens, int completionTokens) {
                throw new IllegalStateException("配额存储炸了");
            }
        };
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = response(usage(1, 1), "m");
        given(chain.nextCall(any())).willReturn(response);

        ChatClientResponse returned = new UsageMeteringAdvisor(exploding).adviseCall(request(7L), chain);

        assertThat(returned).isSameAs(response);
    }

    // ------------------------------------------------------------------
    // 装配：真正跑一遍 ChatClient

    @Test
    @DisplayName("★ 顺序：绝不能是 LOWEST_PRECEDENCE——与链条终端同序的顾问会被静默跳过")
    void orderMustNotCollideWithTheChainTerminal() {
        // 实测（2026-10-02）：order == Ordered.LOWEST_PRECEDENCE 的顾问永远不会被调用，
        // 而框架的 ChatModelCallAdvisor（真正调模型的那一个）用的就是这个值。
        // 那种失效完全无声：模型照调、回答照回、日志全无，只是记账永远是 0。
        // 所以这里把"必须小于 LOWEST_PRECEDENCE"钉死，而不是靠注释提醒。
        assertThat(new UsageMeteringAdvisor((userId, prompt, completion) -> {
        }).getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    @DisplayName("★ 装配：ChatClientFactory 挂的默认顾问真的生效（不是被请求级 advisors 挤掉）")
    void factoryRegistersTheAdvisorAsADefaultAdvisor() {
        RecordingRecorder recorder = new RecordingRecorder();
        ChatModel fakeModel = new FixedResponseChatModel(chatResponse(usage(100, 20), "fake-model"));
        ChatClientFactory factory = new ChatClientFactory(properties(), registry(fakeModel), recorder);

        ChatClient client = factory.get(AiModelProperties.Role.CHAT_DEFAULT);
        // 特意再挂一个请求级 advisor：真实业务代码就是这么用的
        // （聊天记忆顾问 + 会话 key），要证明两者共存而不是互相覆盖
        client.prompt()
                .user("你好")
                .advisors(a -> a.advisors(new NoopAdvisor()).param(UserContextKeys.USER_ID, 42L))
                .call()
                .chatResponse();

        assertThat(recorder.records).containsExactly("user=42 prompt=100 completion=20");
    }

    @Test
    @DisplayName("不传 recorder 的 2 参构造照常可用（既有测试与纯 AI 层不被迫提供配额）")
    void twoArgConstructorStillWorks() {
        ChatClientFactory factory = new ChatClientFactory(properties(),
                registry(new FixedResponseChatModel(chatResponse(usage(1, 1), "m"))));

        ChatResponse response = factory.get(AiModelProperties.Role.CHAT_DEFAULT)
                .prompt().user("你好").call().chatResponse();

        assertThat(response.getMetadata().getModel()).isEqualTo("m");
    }

    // ------------------------------------------------------------------
    // 脚手架

    /** 逐笔记录，便于断言"记了几笔、记成什么"。 */
    private static final class RecordingRecorder implements ChatUsageRecorder {

        private final List<String> records = new ArrayList<>();
        private final List<String> unattributed = new ArrayList<>();

        @Override
        public void record(Long userId, int promptTokens, int completionTokens) {
            records.add("user=%d prompt=%d completion=%d".formatted(userId, promptTokens, completionTokens));
        }

        @Override
        public void recordUnattributed(String model) {
            unattributed.add(model);
        }
    }

    /** 什么都不做的请求级顾问：只为了让"默认顾问 + 请求级顾问"共存这一条被测到。 */
    private static final class NoopAdvisor
            implements org.springframework.ai.chat.client.advisor.api.CallAdvisor,
            org.springframework.ai.chat.client.advisor.api.StreamAdvisor {

        @Override
        public String getName() {
            return "test-noop";
        }

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(request);
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            return chain.nextStream(request);
        }
    }

    /** 固定返回一条既有文本又有用量的响应：不联网、不依赖任何模型。 */
    private static final class FixedResponseChatModel implements ChatModel {

        private final ChatResponse response;

        private FixedResponseChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(response);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            // 真实模型（AiModelRegistry 里构建的）给的都是 ToolCallingChatOptions；
            // 这里跟着给同一个类型，免得框架的工具顾问在做类型转换时踩空
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static AiModelProperties properties() {
        AiModelProperties properties = new AiModelProperties();
        properties.setRoles(Map.of(AiModelProperties.Role.CHAT_DEFAULT, "fake:model"));
        return properties;
    }

    private static AiModelRegistry registry(ChatModel model) {
        AiModelRegistry registry = mock(AiModelRegistry.class);
        given(registry.requireChatModel("fake:model")).willReturn(model);
        return registry;
    }

    private static ChatClientRequest request(Long userId) {
        return ChatClientRequest.builder()
                .prompt(new Prompt("hi"))
                .context(Map.of(UserContextKeys.USER_ID, userId))
                .build();
    }

    private static ChatClientResponse response(Usage usage, String model) {
        return ChatClientResponse.builder().chatResponse(chatResponse(usage, model)).build();
    }

    private static ChatResponse chatResponse(Usage usage, String model) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .metadata(ChatResponseMetadata.builder().model(model).usage(usage).build())
                .build();
    }

    private static Usage usage(int promptTokens, int completionTokens) {
        return new DefaultUsage(promptTokens, completionTokens);
    }
}
