package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.rag.RagAnswer;
import com.lky.nexus.knowledge.rag.RagAnswerService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 问答接口（非流式 MVP）。
 *
 * <p>流式版本会复用已有的 SSE 通道（{@code /api/chat/stream}），但引用需要等回答结束才能解析，
 * 因此先在非流式上把链路跑通——"引用能不能点开原文"比"字是不是一个个蹦出来"重要得多。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@Validated
public class KnowledgeRagController {

    private static final int MAX_TOP_K = 30;

    private final RagAnswerService ragAnswerService;
    private final com.lky.nexus.app.knowledge.service.RagStreamService ragStreamService;

    public KnowledgeRagController(RagAnswerService ragAnswerService,
                                  com.lky.nexus.app.knowledge.service.RagStreamService ragStreamService) {
        this.ragAnswerService = ragAnswerService;
        this.ragStreamService = ragStreamService;
    }

    /** 基于知识库回答问题，返回回答与引用。 */
    @PostMapping("/{knowledgeBaseId}/ask")
    public Result<RagAnswer> ask(@PathVariable Long knowledgeBaseId,
                                 @RequestBody AskRequest request) {
        return Result.ok(ragAnswerService.ask(CurrentUser.requireId(), knowledgeBaseId,
                request.question(), requestedTopK(request), toTurns(request.history())));
    }

    /**
     * 调用方没传 topK 就返回 null（= 用知识库配置），而不是由控制器补一个写死的数。
     *
     * <p>原先这里写死 {@code 12}，于是知识库的 {@code retrieval_top_k} 永远没机会生效——
     * 界面上的"检索条数"只对调试检索面板有用，对问答完全不起作用。
     * 现在"没传"是一等公民：请求 &gt; 知识库配置 &gt; 全局默认。
     * 传了值仍然夹到 [1, MAX_TOP_K]（问答的召回条数直接决定成本，这里不能放任）。
     */
    private Integer requestedTopK(AskRequest request) {
        return request.topK() == null ? null : Math.clamp(request.topK(), 1, MAX_TOP_K);
    }

    /**
     * 前端只传"上一轮的问题 + 回答"，服务端统一收敛成"最多 3 轮、回答只留开头 300 字"。
     *
     * <p>规则（含历史取哪几轮、截断多长）放在 {@link RagAnswerService.RagTurn} 里，
     * 因为它同时服务这条接口与会话流式（{@code /api/chat/stream} 的 RAG 模式从 ChatMemory 取历史）——
     * 两处各写一份，迟早出现"同一个问题在不同入口看到的历史长度不一样"。
     *
     * <p>顺带修正：原先 {@code limit(3)} 取的是**最早**的 3 轮，而只有最近几轮才有助于消解指代
     * （"那它有什么缺点？"指的是上一轮问的东西）。现在取最近的 3 轮。
     */
    private List<RagAnswerService.RagTurn> toTurns(List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return RagAnswerService.RagTurn.lastTurns(history.stream()
                .map(turn -> RagAnswerService.RagTurn.of(turn.question(), turn.answer()))
                .toList());
    }

    /** 流式问答（SSE）：事件顺序 meta → delta… → done（done 里带引用与留档 id）。 */
    @PostMapping(value = "/{knowledgeBaseId}/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter askStream(
            @PathVariable Long knowledgeBaseId,
            @RequestBody AskRequest request) {
        return ragStreamService.stream(CurrentUser.requireId(), knowledgeBaseId,
                request.question(), requestedTopK(request), toTurns(request.history()));
    }

    /** {@code topK} 可省略：省略时用知识库自身的 {@code retrieval_top_k}。 */
    public record AskRequest(@NotBlank String question, Integer topK, List<HistoryTurn> history) {
    }

    /** 一轮历史问答（前端把上一轮的问题与回答回传，服务端不维护会话状态）。 */
    public record HistoryTurn(String question, String answer) {
    }

    /** 最近的问答记录（含引用），用于回看"当时依据了哪些资料"。 */
    @org.springframework.web.bind.annotation.GetMapping("/{knowledgeBaseId}/qa-history")
    public Result<List<RagAnswer>> history(
            @PathVariable Long knowledgeBaseId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit) {
        return Result.ok(ragAnswerService.listHistory(CurrentUser.requireId(), knowledgeBaseId, limit));
    }
}
