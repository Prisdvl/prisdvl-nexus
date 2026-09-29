package com.lky.nexus.knowledge.rag;

import java.util.List;

/**
 * RAG 问答服务：检索 → 重排 → 组装 → 生成 → 引用。
 */
public interface RagAnswerService {

    /**
     * 基于知识库回答问题（并留档）。
     *
     * @param topK    召回条数（重排与上下文预算会在其基础上收窄）
     * @param history 之前几轮问答（可为空）。用于"追问"：让模型知道上一轮问了什么，
     *                否则"那它有什么缺点？"这类省略主语的追问会被当成独立问题。
     */
    RagAnswer ask(Long userId, Long knowledgeBaseId, String question, int topK, List<RagTurn> history);

    /** 最近的问答记录（倒序）。 */
    List<RagAnswer> listHistory(Long userId, Long knowledgeBaseId, int limit);

    /**
     * 一轮历史问答。
     *
     * @param answerSummary 上一轮回答的摘要（只取开头一段，避免把上下文撑爆）
     */
    record RagTurn(String question, String answerSummary) {
    }
}
