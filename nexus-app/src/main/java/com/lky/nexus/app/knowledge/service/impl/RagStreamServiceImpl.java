package com.lky.nexus.app.knowledge.service.impl;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.chat.stream.SseEventNames;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.app.knowledge.service.RagStreamService;
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

/**
 * RAG 流式回答实现。
 *
 * <p>复用 Phase 1 已建立的 SSE 约定（事件名、{@code Flux} + {@code subscribeOn(boundedElastic)}、
 * 终态只发送一次的 AtomicBoolean 保护），所以前端只需要一套解析逻辑。
 *
 * <p>与同步回答共用同一份检索与提示词（{@link RagAnswerService#prepare} /
 * {@link RagAnswerService#systemPrompt()}）——**两条路径绝不能各写一份**，
 * 否则同一个人问同一个问题会得到两种答案，而且很难发现。
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

    @Override
    public SseEmitter stream(Long userId, Long knowledgeBaseId, String question, Integer topK,
                             List<RagAnswerService.RagTurn> history) {
        long start = System.currentTimeMillis();
        String requestId = RequestIdHolder.current();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        // ① 检索 + 重排 + 组装（同步完成，百毫秒级），把资料与引用先告诉前端
        PreparedAnswer prepared = ragAnswerService.prepare(userId, knowledgeBaseId, question, topK, history);
        send(emitter, SseEventNames.META, Map.of(
                "requestId", requestId,
                "citations", prepared.usedHits().isEmpty() ? List.of() : citationPreview(prepared),
                "contextTokens", prepared.contextTokens(),
                "truncated", prepared.truncated(),
                "reranked", prepared.reranked(),
                "empty", prepared.empty()));

        if (prepared.empty()) {
            // 没有资料就不调用模型（省钱，也从根上避免无资料硬答），但仍走完整的流式事件序列，
            // 让前端只有一套处理逻辑
            String text = "资料中没有相关内容（知识库尚未导入或未建立索引）。";
            send(emitter, SseEventNames.DELTA, Map.of("text", text));
            RagAnswer record = ragAnswerService.recordAnswer(userId, knowledgeBaseId, question, text,
                    List.of(), 0, 0, false, System.currentTimeMillis() - start);
            send(emitter, SseEventNames.DONE, Map.of("finishReason", "empty",
                    "recordId", String.valueOf(record.recordId()), "citations", List.of()));
            emitter.complete();
            return emitter;
        }

        // ② 流式生成
        StringBuilder assembled = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        ChatClient client = chatClientFactory.get(AiModelProperties.Role.CHAT_DEFAULT);

        Flux<ChatResponse> flux = client.prompt()
                .system(ragAnswerService.systemPrompt())
                .user(ragAnswerService.userMessage(question, prepared, history))
                .stream()
                .chatResponse();

        Disposable subscription = flux.subscribeOn(Schedulers.boundedElastic()).subscribe(
                chunk -> {
                    String text = textOf(chunk);
                    if (text != null && !text.isEmpty()) {
                        assembled.append(text);
                        send(emitter, SseEventNames.DELTA, Map.of("text", text));
                    }
                },
                error -> {
                    // 生成失败：把已收到的部分落库（部分内容对用户仍有价值），并明确告知失败
                    log.error("RAG 流式生成失败 requestId={}, kbId={}", requestId, knowledgeBaseId, error);
                    if (finished.compareAndSet(false, true)) {
                        RagAnswer record = ragAnswerService.recordAnswer(userId, knowledgeBaseId, question,
                                assembled.toString(), prepared.usedHits(), prepared.contextTokens(),
                                prepared.truncated(), prepared.reranked(), System.currentTimeMillis() - start);
                        send(emitter, SseEventNames.DONE, Map.of(
                                "finishReason", "error",
                                "error", String.valueOf(error.getMessage()),
                                "recordId", String.valueOf(record.recordId()),
                                "citations", record.citations()));
                        emitter.complete();
                    }
                },
                () -> {
                    if (finished.compareAndSet(false, true)) {
                        RagAnswer record = ragAnswerService.recordAnswer(userId, knowledgeBaseId, question,
                                assembled.toString(), prepared.usedHits(), prepared.contextTokens(),
                                prepared.truncated(), prepared.reranked(), System.currentTimeMillis() - start);
                        // 引用在结束时才可能解析出来（要读完整回答里的 [n]），因此放在 done 事件里
                        send(emitter, SseEventNames.DONE, Map.of(
                                "finishReason", "stop",
                                "recordId", String.valueOf(record.recordId()),
                                "citations", record.citations()));
                        log.info("RAG 流式完成 requestId={}, 回答 {} 字, 引用 {} 条, 耗时 {} ms",
                                requestId, assembled.length(), record.citations().size(),
                                System.currentTimeMillis() - start);
                        emitter.complete();
                    }
                });

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(throwable -> subscription.dispose());
        return emitter;
    }

    /** meta 事件里的引用预览：只有位置信息，正文随 done 事件一起给（避免 meta 过大）。 */
    private List<Map<String, Object>> citationPreview(PreparedAnswer prepared) {
        return prepared.usedHits().stream().map(hit -> {
            Map<String, Object> item = new HashMap<>();
            item.put("chunkId", String.valueOf(hit.chunkId()));
            item.put("documentId", String.valueOf(hit.documentId()));
            item.put("documentTitle", hit.documentTitle());
            item.put("headingPath", hit.headingPath());
            return item;
        }).toList();
    }

    private String textOf(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return null;
        }
        return chunk.getResult().getOutput().getText();
    }

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            // 客户端断开时 send 会抛异常；此时生成也应停止（订阅在 onError/onCompletion 里释放）
            log.debug("SSE 发送失败，判定客户端已断开：{}", ex.getMessage());
            throw new IllegalStateException(ex);
        }
    }
}
