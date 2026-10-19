package com.lky.nexus.app.knowledge.service.impl;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.chat.stream.RetrievalEvent;
import com.lky.nexus.app.chat.stream.SseEventNames;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.app.knowledge.service.RagStreamService;
import com.lky.nexus.common.security.UserContextKeys;
import com.lky.nexus.knowledge.rag.PreparedAnswer;
import com.lky.nexus.knowledge.rag.RagAnswer;
import com.lky.nexus.knowledge.rag.RagAnswerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 流式回答实现。
 *
 * <p>复用 Phase 1 已建立的 SSE 约定（事件名、{@code Flux} + {@code subscribeOn(boundedElastic)}、
 * 终态只发送一次的 AtomicBoolean 保护），所以前端只需要一套解析逻辑。
 *
 * <p>与同步回答共用同一份检索与提示词（{@link RagAnswerService#prepare} /
 * {@link RagAnswerService#systemPrompt()}）——**两条路径绝不能各写一份**，
 * 否则同一个人问同一个问题会得到两种答案，而且很难发现。
 *
 * <p><b>2026-10-19 修（roadmap 待办 #1：中止后误报未预期异常）</b>：SSE 发送出口从
 * "抛 {@code IllegalStateException}" 改成 {@link ClientSink} 的"判定客户端断开"——
 * 关标签页不再被记成一次生成失败，也不再从 Flux 回调里逃出去变成全局"未预期异常"。
 */
@Service
public class RagStreamServiceImpl implements RagStreamService {

    private static final Logger log = LoggerFactory.getLogger(RagStreamServiceImpl.class);

    /** SSE 超时：给足生成时间（流式下用户已在看字，超时只需兜底）。 */
    private static final long SSE_TIMEOUT_MILLIS = 300_000L;

    private final RagAnswerService ragAnswerService;
    private final ChatClientFactory chatClientFactory;

    public RagStreamServiceImpl(RagAnswerService ragAnswerService, ChatClientFactory chatClientFactory) {
        this.ragAnswerService = ragAnswerService;
        this.chatClientFactory = chatClientFactory;
    }

    /**
     * 创建这一轮的 SSE 出口。
     *
     * <p>抽成方法只为测试：{@code SseEmitter} 的发送接口是 final 语义的响应写出，
     * 只有换成"会抛 broken pipe 的假响应"才复现得出客户端断开那一格
     * （见 {@code RagStreamServiceImplTest}）。生产上没有任何子类。
     */
    protected SseEmitter newEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MILLIS);
    }

    @Override
    public SseEmitter stream(Long userId, Long knowledgeBaseId, String question, Integer topK,
                             List<RagAnswerService.RagTurn> history) {
        long start = System.currentTimeMillis();
        String requestId = RequestIdHolder.current();
        SseEmitter emitter = newEmitter();

        // ① 检索 + 重排 + 组装（同步完成，百毫秒级），把资料与引用先告诉前端
        PreparedAnswer prepared = ragAnswerService.prepare(userId, knowledgeBaseId, question, topK, history);

        StringBuilder assembled = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        ClientSink sink = new ClientSink(emitter, () -> retainPartialAnswer(userId, knowledgeBaseId, question,
                prepared, assembled, finished, start, requestId));

        sink.send(SseEventNames.META, Map.of(
                "requestId", requestId,
                "citations", prepared.usedHits().isEmpty() ? List.of() : citationPreview(prepared),
                "contextTokens", prepared.contextTokens(),
                "truncated", prepared.truncated(),
                "reranked", prepared.reranked(),
                "empty", prepared.empty()));
        // ② 检索过程事件：必须在第一个 delta 之前到达，前端才能先显示"检索到 N 个片段"。
        //    这里是同步发送（订阅还没建立），顺序是结构保证的，不依赖时序运气。
        //    empty 也照发：count=0 是要告诉用户的信息，省略事件会让前端一直等。
        // 这条链路固定单库（路径里的 {id}），所以这里永远是单元素列表——但负载形状
        // 与会话流式那条多库链路保持一致：事件有两个生产者，形状分叉就得有两套解析逻辑
        sink.send(SseEventNames.RETRIEVAL, RetrievalEvent.of(List.of(knowledgeBaseId), prepared));

        if (sink.aborted()) {
            // 第一个事件就没送出去 = 客户端早就走了：没有上游可停、也没有内容可留档
            return emitter;
        }

        if (prepared.empty()) {
            // 没有资料就不调用模型（省钱，也从根上避免无资料硬答），但仍走完整的流式事件序列，
            // 让前端只有一套处理逻辑
            String text = RagAnswerService.EMPTY_ANSWER;
            sink.send(SseEventNames.DELTA, Map.of("text", text));
            RagAnswer record = ragAnswerService.recordAnswer(userId, knowledgeBaseId, question, text,
                    List.of(), 0, 0, false, System.currentTimeMillis() - start);
            sink.send(SseEventNames.DONE, Map.of("finishReason", "empty",
                    "recordId", String.valueOf(record.recordId()), "citations", List.of()));
            sink.complete();
            return emitter;
        }

        // ② 流式生成
        // 传 userId：用户配了自己的对话模型就走他自己的凭据，未配置回落服务端全局模型。
        // 注意检索（嵌入）仍走服务端全局配置——嵌入维度建库时冻结，不随用户 key 切换。
        ChatClient client = chatClientFactory.get(AiModelProperties.Role.CHAT_DEFAULT, userId);

        Flux<ChatResponse> flux = client.prompt()
                .system(ragAnswerService.systemPrompt())
                .user(ragAnswerService.userMessage(question, prepared, history))
                // token 记账归属（5.6）：本条链路的订阅发生在 boundedElastic 线程上，
                // 请求线程的 SecurityContext 在那里是空的——所以只能靠 Advisor 上下文传递
                .advisors(a -> a.param(UserContextKeys.USER_ID, userId))
                .stream()
                .chatResponse();

        Disposable subscription = flux.subscribeOn(Schedulers.boundedElastic()).subscribe(
                chunk -> {
                    String text = textOf(chunk);
                    if (text != null && !text.isEmpty()) {
                        assembled.append(text);
                        sink.send(SseEventNames.DELTA, Map.of("text", text));
                    }
                },
                error -> {
                    // 客户端已断开时不会有 error 到达（断开会 dispose 上游），这里再挡一道：
                    // 即便真到了，也不能把"用户关页面"记成一次生成失败
                    if (sink.aborted()) {
                        return;
                    }
                    // 生成失败：把已收到的部分落库（部分内容对用户仍有价值），并明确告知失败。
                    // 两条 2026-10-01 修正：
                    // ① 给用户看的话由 FailureClassifier 统一给（原来是把 error.getMessage()
                    //    原样塞进 done 帧，内网地址/上游响应体会直接显示在界面上）；
                    // ② 一个字都没生成出来时不落档——否则问答历史里会多出一条空回答，
                    //    既看不出为什么是空的，也没有任何内容可回看（update.txt §十二 的"不留脏数据"）。
                    log.error("RAG 流式生成失败 requestId={}, kbId={}", requestId, knowledgeBaseId, error);
                    if (finished.compareAndSet(false, true)) {
                        FailureClassifier.Failure failure = FailureClassifier.classify(error);
                        Map<String, Object> done = new HashMap<>();
                        done.put("finishReason", "error");
                        done.put("error", failure.message());
                        done.put("errorCode", failure.code().getCode());
                        done.put("retryable", failure.retryable());
                        done.put("citations", List.of());
                        if (assembled.isEmpty()) {
                            log.warn("生成失败且没有产出任何内容，不写问答留档 requestId={}", requestId);
                        } else {
                            RagAnswer record = ragAnswerService.recordAnswer(userId, knowledgeBaseId, question,
                                    assembled.toString(), prepared.usedHits(), prepared.contextTokens(),
                                    prepared.truncated(), prepared.reranked(),
                                    System.currentTimeMillis() - start);
                            done.put("recordId", String.valueOf(record.recordId()));
                            done.put("citations", record.citations());
                        }
                        sink.send(SseEventNames.DONE, done);
                        sink.complete();
                    }
                },
                () -> {
                    if (finished.compareAndSet(false, true)) {
                        RagAnswer record = ragAnswerService.recordAnswer(userId, knowledgeBaseId, question,
                                assembled.toString(), prepared.usedHits(), prepared.contextTokens(),
                                prepared.truncated(), prepared.reranked(), System.currentTimeMillis() - start);
                        // 引用在结束时才可能解析出来（要读完整回答里的 [n]），因此放在 done 事件里
                        sink.send(SseEventNames.DONE, Map.of(
                                "finishReason", "stop",
                                "recordId", String.valueOf(record.recordId()),
                                "citations", record.citations()));
                        log.info("RAG 流式完成 requestId={}, 回答 {} 字, 引用 {} 条, 耗时 {} ms",
                                requestId, assembled.length(), record.citations().size(),
                                System.currentTimeMillis() - start);
                        sink.complete();
                    }
                });

        sink.bind(subscription);
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(throwable -> subscription.dispose());
        return emitter;
    }

    /**
     * 客户端断开时把已生成的部分落库（与会话流式的 {@code abandon()} 同口径：
     * 内容对用户仍有价值，且上游已经停了，不会再有别的终态来写这一条）。
     *
     * <p>刻意只记 {@code info}：断开是用户行为，不是服务端故障，不该进错误日志与错误计数。
     */
    private void retainPartialAnswer(Long userId, Long knowledgeBaseId, String question, PreparedAnswer prepared,
                                     StringBuilder assembled, AtomicBoolean finished, long start, String requestId) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        if (assembled.isEmpty()) {
            log.debug("客户端断开且没有产出任何内容，不写问答留档 requestId={}", requestId);
            return;
        }
        RagAnswer record = ragAnswerService.recordAnswer(userId, knowledgeBaseId, question, assembled.toString(),
                prepared.usedHits(), prepared.contextTokens(), prepared.truncated(), prepared.reranked(),
                System.currentTimeMillis() - start);
        log.info("客户端断开，已停止生成并保留部分回答 requestId={}, 回答 {} 字, recordId={}, 耗时 {} ms",
                requestId, assembled.length(), record.recordId(), System.currentTimeMillis() - start);
    }

    /** meta 事件里的引用预览：只有位置信息，正文随 done 事件一起给（避免 meta 过大）。 */
    private List<Map<String, Object>> citationPreview(PreparedAnswer prepared) {
        return RetrievalEvent.citationPreview(prepared);
    }

    private String textOf(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return null;
        }
        return chunk.getResult().getOutput().getText();
    }

    /**
     * SSE 发送出口：把"客户端已断开"与"生成失败"彻底分开。
     *
     * <p><b>为什么不能再抛异常</b>：原来 {@code send} 把发送失败包成
     * {@code IllegalStateException} 往上抛。这个异常从 Flux 的 {@code onNext} 里逃出去之后，
     * 会被当成模型/检索出错的信号，先写一条 {@code log.error("RAG 流式生成失败")}，
     * 若是错误分支里再发 done 帧则会二次抛出并最终落到「全局未预期异常」处理器
     * （HTTP 500 + ERRORS 计数）。用户只是关掉了页面，服务端却报了一条不存在的故障。
     *
     * <p>现在的语义与会话流式一致：发送失败 = 客户端断开 → 置位 {@link #aborted}、
     * dispose 上游（否则模型继续生成、继续计费）、把已生成的部分落库，
     * 且此后不再尝试推送、不再走"失败"分支。
     */
    private static final class ClientSink {

        private static final Logger log = LoggerFactory.getLogger(ClientSink.class);

        private final SseEmitter emitter;
        private final Runnable onAbort;
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        ClientSink(SseEmitter emitter, Runnable onAbort) {
            this.emitter = emitter;
            this.onAbort = onAbort;
        }

        /** 订阅建立后登记上游：断开了才有东西可停。 */
        void bind(Disposable disposable) {
            subscription.set(disposable);
        }

        boolean aborted() {
            return aborted.get();
        }

        /** 发送一个事件；客户端已断开则返回 false 并停掉上游，绝不向上抛异常。 */
        boolean send(String eventName, Object payload) {
            if (aborted.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
                return true;
            } catch (Exception ex) {
                // 客户端断开（关标签页 / 网络断）会让 send 抛异常。这时必须停掉上游，
                // 否则模型会继续生成、继续计费；也不能把它当成"生成失败"上报。
                log.debug("SSE 发送失败，判定客户端已断开：{}", ex.getMessage());
                if (aborted.compareAndSet(false, true)) {
                    Disposable disposable = subscription.get();
                    if (disposable != null && !disposable.isDisposed()) {
                        disposable.dispose();
                    }
                    onAbort.run();
                }
                return false;
            }
        }

        /** 关闭 SSE：断开后不再动（对已断开的响应再 complete 只会再抛一次）。 */
        void complete() {
            if (aborted.get()) {
                return;
            }
            try {
                emitter.complete();
            } catch (Exception ex) {
                log.debug("关闭 SSE 时出现异常（可忽略）：{}", ex.getMessage());
            }
        }
    }
}
