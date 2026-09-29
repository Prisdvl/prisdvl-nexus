package com.lky.nexus.knowledge.rag;

/**
 * RAG 问答服务：检索 → 重排 → 组装 → 生成 → 引用。
 */
public interface RagAnswerService {

    /**
     * 基于知识库回答问题。
     *
     * @param topK 召回条数（重排与上下文预算会在其基础上收窄）
     */
    RagAnswer ask(Long userId, Long knowledgeBaseId, String question, int topK);
}
