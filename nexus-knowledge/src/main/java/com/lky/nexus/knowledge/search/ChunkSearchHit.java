package com.lky.nexus.knowledge.search;

/**
 * 检索命中项。
 *
 * @param chunkId 切片 id
 * @param score   相关性得分（BM25 与向量分数各自量纲不同，融合在 Phase 3.6 用 RRF 处理）
 * @param source  "vector" / "bm25"，用于观测与调试（前端也可标注来源）
 */
public record ChunkSearchHit(Long chunkId, double score, String source) {
}
