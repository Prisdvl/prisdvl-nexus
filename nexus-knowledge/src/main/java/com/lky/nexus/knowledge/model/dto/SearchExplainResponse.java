package com.lky.nexus.knowledge.model.dto;

import java.util.List;

/**
 * 检索过程剖析（{@code POST /api/search/explain}）的响应。
 *
 * <p>用途只有一个：<b>调参与演示</b>。所以它把两路召回的原始顺序、融合后的名次、
 * 以及重排后的分数全部摊开——而不是像 {@code /api/search} 那样只给最终结果。
 *
 * <p>三个"不"：<b>不落库</b>（只读）、<b>不裁剪</b>（两路原始结果照实返回，
 * 否则看不出"为什么这条没进最终结果"）、<b>不改语义</b>（{@code fused} 就是线上
 * HYBRID 策略会看到的那份列表，用的是同一个 {@code RrfFusion}）。
 *
 * @param vector     向量路（pgvector）的原始命中，按相似度降序
 * @param bm25       关键词路（ES）的原始命中，按 BM25 降序
 * @param fused      RRF 融合后的列表，按融合分降序，{@code rank} 从 1 开始
 * @param rerank     重排结果；未请求重排或重排失败时为空列表
 * @param tookMs     服务端耗时（毫秒）
 * @param degradedKbs 失败的库（与 {@code /api/search} 同一语义）
 */
public record SearchExplainResponse(
        List<ExplainPathHit> vector,
        List<ExplainPathHit> bm25,
        List<ExplainFusedHit> fused,
        List<ExplainRerankHit> rerank,
        long tookMs,
        List<DegradedKb> degradedKbs) {
}
