package com.lky.nexus.knowledge.rag;

import java.util.List;

/**
 * RAG 问答服务：检索 → 重排 → 组装 → 生成 → 引用。
 */
public interface RagAnswerService {

    /**
     * 基于知识库回答问题（并留档）。
     *
     * @param topK    召回条数（重排与上下文预算会在其基础上收窄）；
     *                <b>null 表示没指定</b>，这时用知识库自身的 retrieval_top_k
     *                （相似度阈值同理取知识库的 similarity_threshold）
     * @param history 之前几轮问答（可为空）。用于"追问"：让模型知道上一轮问了什么，
     *                否则"那它有什么缺点？"这类省略主语的追问会被当成独立问题。
     */
    RagAnswer ask(Long userId, Long knowledgeBaseId, String question, Integer topK, List<RagTurn> history);

    /** 最近的问答记录（倒序）。 */
    List<RagAnswer> listHistory(Long userId, Long knowledgeBaseId, int limit);

    /**
     * 准备阶段：检索 + 重排 + 上下文组装（同步与流式两条路径共用）。
     *
     * <p>流式回答必须先拿到资料块才能开始推流，因此这一半要能单独调用。
     *
     * @param topK 召回条数；null = 用知识库配置（同 {@link #ask}）
     */
    PreparedAnswer prepare(Long userId, Long knowledgeBaseId, String question, Integer topK,
                           List<RagTurn> history);

    /**
     * 把一次问答留档（流式回答在结束后调用）。
     *
     * @param usedHits 资料块里的命中项（用于生成引用编号）
     */
    RagAnswer recordAnswer(Long userId, Long knowledgeBaseId, String question, String answer,
                           List<com.lky.nexus.knowledge.service.ChunkSearchService.SearchHit> usedHits,
                           int contextTokens, int truncated, boolean reranked, long costMillis);

    /** 系统提示（同步与流式共用，保证两条路径的约束完全一致）。 */
    String systemPrompt();

    /** 用户消息（历史 + 资料 + 问题）。 */
    String userMessage(String question, PreparedAnswer prepared, List<RagTurn> history);

    /**
     * 一轮历史问答。
     *
     * @param answerSummary 上一轮回答的摘要（只取开头一段，避免把上下文撑爆）
     */
    record RagTurn(String question, String answerSummary) {
    }
}
