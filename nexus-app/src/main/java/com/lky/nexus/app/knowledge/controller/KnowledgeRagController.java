package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.rag.RagAnswer;
import com.lky.nexus.knowledge.rag.RagAnswerService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public KnowledgeRagController(RagAnswerService ragAnswerService) {
        this.ragAnswerService = ragAnswerService;
    }

    /** 基于知识库回答问题，返回回答与引用。 */
    @PostMapping("/{knowledgeBaseId}/ask")
    public Result<RagAnswer> ask(@PathVariable Long knowledgeBaseId,
                                 @RequestBody AskRequest request) {
        int topK = request.topK() == null ? 12 : Math.clamp(request.topK(), 1, MAX_TOP_K);
        return Result.ok(ragAnswerService.ask(CurrentUser.requireId(), knowledgeBaseId,
                request.question(), topK));
    }

    public record AskRequest(@NotBlank String question, Integer topK) {
    }

    /** 最近的问答记录（含引用），用于回看"当时依据了哪些资料"。 */
    @org.springframework.web.bind.annotation.GetMapping("/{knowledgeBaseId}/qa-history")
    public Result<java.util.List<RagAnswer>> history(
            @PathVariable Long knowledgeBaseId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit) {
        return Result.ok(ragAnswerService.listHistory(CurrentUser.requireId(), knowledgeBaseId, limit));
    }
}
