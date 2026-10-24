package com.lky.nexus.knowledge.model.dto;

/**
 * 单路（向量 / 关键词）的原始命中，保留上游名次。
 *
 * <p>{@code rank} 是<b>该路</b>内的名次（从 1 开始），不是全局名次：
 * 融合分就是由这两个名次算出来的，摊开它们才能让人手算校验。
 *
 * @param kbId          来源库
 * @param chunkId       切片 id
 * @param documentId    文档 id
 * @param documentTitle 文档标题（切片已删除时尽力为 {@code null}，条目仍保留）
 * @param headingPath   标题路径
 * @param snippet       正文片段
 * @param score         该路原始分（向量相似度 / BM25 分）
 * @param rank          该路名次，从 1 开始
 */
public record ExplainPathHit(
        Long kbId,
        Long chunkId,
        Long documentId,
        String documentTitle,
        String headingPath,
        String snippet,
        double score,
        int rank) {
}
