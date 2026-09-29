package com.lky.nexus.knowledge.vector;

/**
 * 向量检索命中项。
 *
 * @param chunkId MySQL kb_chunk.id
 * @param score   余弦相似度（1 - 余弦距离），越大越相似；范围约 [-1, 1]
 */
public record VectorSearchHit(Long chunkId, double score) {
}
