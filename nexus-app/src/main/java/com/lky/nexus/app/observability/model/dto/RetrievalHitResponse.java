package com.lky.nexus.app.observability.model.dto;

/**
 * 一条检索命中。
 *
 * <p>{@code snippet} 是原文片段，可能较长——调用方（观测页）自己截断显示，
 * 服务端截断反而会让人以为检索内容就这么短。
 */
public record RetrievalHitResponse(
        Long knowledgeBaseId,
        String documentName,
        String headingPath,
        Double score,
        String snippet) {
}
