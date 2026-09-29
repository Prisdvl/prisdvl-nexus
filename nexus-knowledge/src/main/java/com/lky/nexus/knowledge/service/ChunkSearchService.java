package com.lky.nexus.knowledge.service;

import java.util.List;

/**
 * 切片检索服务。
 *
 * <p>当前只实现关键词（BM25）一路；Phase 3.4 加入向量一路，
 * Phase 3.6 用 RRF 融合两路结果。接口保持"返回带上下文的命中项"这一契约，
 * 融合逻辑因此不会渗到调用方（对话链路只关心"给我 k 条最相关的切片"）。
 */
public interface ChunkSearchService {

    /** 关键词检索（BM25），返回已补齐正文与文档标题的命中项。 */
    List<SearchHit> searchBm25(Long userId, Long knowledgeBaseId, String query, int topK);

    /**
     * 命中项（已补齐展示与引用所需的信息）。
     *
     * @param chunkId       切片 id
     * @param documentId    文档 id
     * @param documentTitle 文档标题（引用时展示"来自哪篇"）
     * @param headingPath   标题路径（引用时展示"来自哪一节"）
     * @param content       切片正文（用于生成答案与引用片段）
     * @param score         相关性得分
     * @param source        来源：bm25 / vector / hybrid
     */
    record SearchHit(
            Long chunkId,
            Long documentId,
            String documentTitle,
            String headingPath,
            String content,
            double score,
            String source) {
    }
}
