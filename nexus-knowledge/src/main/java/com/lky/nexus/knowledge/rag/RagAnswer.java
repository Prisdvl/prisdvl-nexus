package com.lky.nexus.knowledge.rag;

import java.util.List;

/**
 * RAG 回答。
 *
 * @param answer       模型回答（带 [n] 引用标记）
 * @param citations    引用列表（编号与回答里的 [n] 对应）
 * @param contextTokens 资料块 Token 估算
 * @param truncated    因预算被丢弃的段数
 * @param reranked     是否真的做了重排（false 表示降级为召回顺序）
 * @param costMillis   端到端耗时
 */
public record RagAnswer(
        Long recordId,
        String answer,
        List<RagCitation> citations,
        int contextTokens,
        int truncated,
        boolean reranked,
        long costMillis) {

    /**
     * 一条引用。
     *
     * @param index         资料块编号（回答里的 [n]）
     * @param referenced    回答是否真的引用了它（未引用也返回，便于核对模型有没有漏引）
     * @param documentId    文档 id（前端据此跳转）
     * @param headingPath   标题路径（前端据此定位到小节）
     */
    public record RagCitation(
            int index,
            Long chunkId,
            Long documentId,
            String documentTitle,
            String headingPath,
            String snippet,
            double score,
            boolean referenced) {

        public RagCitation referenced(boolean value) {
            return new RagCitation(index, chunkId, documentId, documentTitle, headingPath,
                    snippet, score, value);
        }
    }
}
