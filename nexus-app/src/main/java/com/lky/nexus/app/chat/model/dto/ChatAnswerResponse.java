package com.lky.nexus.app.chat.model.dto;

/**
 * 一轮问答的结果。
 *
 * <p>同时返回两条消息的 id，前端可以直接把它们插入列表而无需重新拉取历史。
 * {@code citations} 字段留给 Phase 3（RAG 引用），本步恒为空。
 */
public record ChatAnswerResponse(
        Long conversationId,
        Long userMessageId,
        Long assistantMessageId,
        String answer,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer latencyMs) {
}
