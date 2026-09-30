package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
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
     * @param topK 返回条数；<b>null 表示"没指定"</b>，这时用知识库自身的
     *             {@code retrieval_top_k} 配置（仍没有则用全局默认 {@code nexus.retrieval.top-k}）。
     *             相似度阈值同理取知识库的 {@code similarity_threshold}——
     *             也就是说，"按库把检索参数调好"这件事不需要调用方配合。
     * @param mode bm25 / vector / hybrid
     */
    List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, Integer topK, SearchMode mode);

    /**
     * 检索（带元数据过滤）。
     *
     * @param filters 标签 / 目录 / 指定文档；{@link RetrievalFilters#none()} 表示不过滤。
     *                三个条件 AND，标签内部 OR，目录含子目录（详见 {@link RetrievalFilters}）。
     */
    List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, Integer topK, SearchMode mode,
                           RetrievalFilters filters);

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
