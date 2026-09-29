package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.search.SearchMode;

import java.util.List;

/**
 * 切片检索服务：关键词（BM25）、向量、以及 RRF 融合三种模式。
 *
 * <p>三种模式都对外暴露是刻意的：融合效果好不好、权重要不要调，
 * 只有在能单独看每一路结果时才判断得了（否则"融合得不错"只是感觉）。
 */
public interface ChunkSearchService {

    /**
     * 检索。
     *
     * @param mode bm25 / vector / hybrid
     */
    List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, int topK, SearchMode mode);

    /**
     * 命中项（已补齐展示与引用所需的信息）。
     *
     * @param score     相关性得分：单路时是该路原始分数，融合时是 RRF 分数
     * @param source    bm25 / vector / hybrid
     * @param matchedBy 命中来源明细（如 {@code ["BM25 #2", "向量 #1"]}），让"为什么排前面"可解释
     */
    record SearchHit(
            Long chunkId,
            Long documentId,
            String documentTitle,
            String headingPath,
            String content,
            double score,
            String source,
            List<String> matchedBy) {
    }
}
