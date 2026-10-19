package com.lky.nexus.app.knowledge.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.knowledge.rag.PreparedAnswer;
import com.lky.nexus.knowledge.rag.RagAnswer;
import com.lky.nexus.knowledge.rag.RagAnswerService;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 流式的<b>中止路径</b>（roadmap 待办 #1：中止后误报未预期异常）。
 *
 * <p>用单元测试而不是 MockMvc 集成测试，是因为这里要断言的正是"响应已经写不进去"这一格：
 * MockMvc 的异步派发总是能把响应读完，永远造不出 broken pipe，而生产事故恰恰发生在
 * 浏览器关标签页、网络断掉的那一刻——那时 {@code emitter.send} 会抛异常。
 *
 * <p>三条用例把"断开"和"失败"钉成两件不同的事：
 * <ol>
 *   <li>断开：不再推送、不再当失败上报（<b>没有任何 ERROR 日志</b>）、停掉上游、保留已生成的部分；</li>
 *   <li>真的上游失败：照旧发 error 帧 + 落库部分内容；</li>
 *   <li>正常结束：只发一次 done，且 complete 一次（终态不重复）。</li>
 * </ol>
 */
@DisplayName("RAG 流式：客户端断开 vs 生成失败")
class RagStreamServiceImplTest {

    private static final long USER_ID = 1001L;
    private static final long KB_ID = 2002L;
    private static final String QUESTION = "缓存击穿怎么解决？";

    private RagAnswerService ragAnswerService;
    private ChatClientFactory chatClientFactory;
    private ListAppender<ILoggingEvent> serviceLogs;

    @BeforeEach
    void setUp() {
        ragAnswerService = mock(RagAnswerService.class);
        chatClientFactory = mock(ChatClientFactory.class);
        RequestIdHolder.set("test-request-id");
        when(ragAnswerService.prepare(any(), any(), anyString(), any(), any())).thenReturn(preparedWithOneHit());
        when(ragAnswerService.systemPrompt()).thenReturn("SYSTEM-PROMPT");
        when(ragAnswerService.userMessage(anyString(), any(), any())).thenReturn("USER-PROMPT");
        when(ragAnswerService.recordAnswer(any(), any(), anyString(), anyString(), any(), anyInt(), anyInt(),
                anyBoolean(), anyLong())).thenReturn(recorded());
        serviceLogs = attach(RagStreamServiceImpl.class);
    }

    @AfterEach
    void tearDown() {
        RequestIdHolder.clear();
        detach(RagStreamServiceImpl.class, serviceLogs);
    }

    @Test
    @DisplayName("客户端断开：不抛异常、不再推送、不报 ERROR、停上游、保留已生成的部分")
    void clientDisconnectIsNotReportedAsGenerationFailure() {
        // meta（同步）与 retrieval（同步）能发出去，第一个 delta 就撞上 broken pipe——
        // 这正是"用户生成到一半关掉页面"的时序
        RecordingEmitter emitter = new RecordingEmitter(3);
        stubModel(Flux.just(chunk("第一段"), chunk("第二段")));

        // 关键断言：stream() 本身不抛异常（旧实现在这里会把 IllegalStateException 抛进回调链）
        newService(emitter).stream(USER_ID, KB_ID, QUESTION, 5, List.of());

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(ragAnswerService, timeout(5_000)).recordAnswer(any(), any(), anyString(), answer.capture(),
                any(), anyInt(), anyInt(), anyBoolean(), anyLong());

        assertThat(answer.getValue())
                .as("断开时保留的是已经生成出来的部分，而不是整段或空串")
                .isEqualTo("第一段");
        assertThat(emitter.eventNames())
                .as("失败之后不再尝试推送任何事件（旧实现会继续发，并在 done 帧上二次抛异常）")
                .containsExactly("meta", "retrieval");
        assertThat(emitter.attempts()).as("只有前两次发送成功，第三次就是那次失败").isEqualTo(3);
        assertThat(emitter.completions()).as("已断开的响应不再 complete").isZero();
        assertThat(errorLogs(serviceLogs))
                .as("断开不是生成失败：日志里不能出现 ERROR（这条 ERROR 曾把一次关页面报成未预期异常）")
                .isEmpty();
    }

    @Test
    @DisplayName("上游真的失败：照旧发 error 帧、落库部分内容、写 ERROR 日志")
    void upstreamFailureStillProducesErrorFrame() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(Integer.MAX_VALUE);
        stubModel(Flux.concat(Flux.just(chunk("第一段")), Flux.error(new IllegalStateException("上游 500"))));

        newService(emitter).stream(USER_ID, KB_ID, QUESTION, 5, List.of());

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(ragAnswerService, timeout(5_000)).recordAnswer(any(), any(), anyString(), answer.capture(),
                any(), anyInt(), anyInt(), anyBoolean(), anyLong());

        assertThat(answer.getValue()).isEqualTo("第一段");
        assertThat(emitter.awaitCompletion(5)).as("失败路径也必须有终态（否则前端一直转圈）").isTrue();
        assertThat(emitter.eventNames()).containsExactly("meta", "retrieval", "delta", "done");
        assertThat(emitter.payloadOf("done"))
                .as("失败要明确告诉前端 finishReason=error，且带上分类后的错误码")
                .containsEntry("finishReason", "error")
                .containsKey("errorCode");
        assertThat(emitter.completions()).isEqualTo(1);
        assertThat(errorLogs(serviceLogs)).as("真失败必须留下 ERROR 日志").hasSize(1);
    }

    @Test
    @DisplayName("正常结束：两次 delta 后只发一次 done，complete 一次")
    void normalCompletionSendsDoneOnce() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter(Integer.MAX_VALUE);
        stubModel(Flux.just(chunk("第一段"), chunk("第二段")));

        newService(emitter).stream(USER_ID, KB_ID, QUESTION, 5, List.of());

        verify(ragAnswerService, timeout(5_000)).recordAnswer(any(), any(), anyString(), anyString(),
                any(), anyInt(), anyInt(), anyBoolean(), anyLong());
        assertThat(emitter.awaitCompletion(5)).isTrue();
        assertThat(emitter.eventNames()).containsExactly("meta", "retrieval", "delta", "delta", "done");
        assertThat(emitter.payloadOf("done")).containsEntry("finishReason", "stop");
        assertThat(emitter.completions()).isEqualTo(1);
        assertThat(errorLogs(serviceLogs)).isEmpty();
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 注入"会在第 N 次发送时断掉"的响应。
     *
     * <p>断开这件事只发生在 {@code emitter.send} 抛异常的那一刻，所以必须把生产代码内部的
     * 响应对象换掉——这也是 {@code RagStreamServiceImpl#newEmitter()} 这唯一一处接缝的由来。
     */
    private RagStreamServiceImpl newService(RecordingEmitter emitter) {
        return new RagStreamServiceImpl(ragAnswerService, chatClientFactory) {
            @Override
            protected SseEmitter newEmitter() {
                return emitter;
            }
        };
    }

    private PreparedAnswer preparedWithOneHit() {
        ChunkSearchService.SearchHit hit = new ChunkSearchService.SearchHit(20001L, 100L, "缓存击穿.md",
                "Redis > 缓存 > 缓存击穿", "互斥锁方案的正文……", 0.83, "hybrid", List.of("BM25 #1"), KB_ID);
        return new PreparedAnswer("【1】互斥锁方案……", List.of(hit), 1200, 0, true, false);
    }

    private RagAnswer recorded() {
        return new RagAnswer(9001L, "互斥锁与逻辑过期。", List.of(), 1200, 0, true, 3000L);
    }

    private void stubModel(Flux<ChatResponse> flux) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClientFactory.get(anyString(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(flux);
    }

    private ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ListAppender<ILoggingEvent> attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
    }

    private List<String> errorLogs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 只记录、不真的写响应；从第 {@code failFromAttempt} 次发送开始抛 broken pipe。 */
    private static final class RecordingEmitter extends SseEmitter {

        private final int failFromAttempt;
        private final List<String> eventNames = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Object> payloads = Collections.synchronizedMap(new LinkedHashMap<>());
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);

        RecordingEmitter(int failFromAttempt) {
            super(5_000L);
            this.failFromAttempt = failFromAttempt;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (attempts.incrementAndGet() >= failFromAttempt) {
                throw new IOException("Broken pipe");
            }
            String name = null;
            Object payload = null;
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                Object data = entry.getData();
                if (data instanceof String text) {
                    // Spring 把"名字行 + data: 前缀"合成同一个字符串（"event:delta\ndata:"），
                    // 所以只取第一行，别把 data: 也当成事件名的一部分
                    if (text.startsWith("event:")) {
                        name = text.substring("event:".length()).split("\n", 2)[0].trim();
                    }
                } else {
                    payload = data;
                }
            }
            if (name != null) {
                eventNames.add(name);
                if (payload != null) {
                    payloads.put(name, payload);
                }
            }
        }

        @Override
        public void complete() {
            completions.incrementAndGet();
            completed.countDown();
        }

        /**
         * 等终态真正发生。
         *
         * <p>{@code verify(mock, timeout)} 只能等到"落库被调用"那一瞬，而 screen 上的
         * {@code done} 帧与 {@code complete()} 就在它后面几行——不等就会读到一个半成品状态，
         * 表现为"这次绿、那次红"的偶发失败（写这条时真的踩到了）。
         */
        boolean awaitCompletion(long seconds) throws InterruptedException {
            return completed.await(seconds, java.util.concurrent.TimeUnit.SECONDS);
        }

        List<String> eventNames() {
            return List.copyOf(eventNames);
        }

        int attempts() {
            return attempts.get();
        }

        int completions() {
            return completions.get();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadOf(String event) {
            Object payload = payloads.get(event);
            assertThat(payload).as("没有 %s 事件的负载", event).isInstanceOf(Map.class);
            return (Map<String, Object>) payload;
        }
    }
}
