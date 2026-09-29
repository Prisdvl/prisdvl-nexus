package com.lky.nexus.knowledge.search;

import java.util.List;

/**
 * 检索索引端口（BM25 / 关键词检索）。
 *
 * <p>与 {@code VectorStore} 并列：向量检索擅长"意思相近"，关键词检索擅长
 * "精确术语、代码标识符、人名地名"。混合检索（Phase 3.6）就是把两者融合，
 * 因此这里刻意保持"只做 BM25"的单一职责，融合逻辑不渗进来。
 */
public interface ChunkIndex {

    /** 确保索引与别名存在（幂等）。 */
    void ensureIndex();

    /**
     * 重建索引：新建物理索引 → 原子切换别名 → 删除旧索引。
     *
     * <p>为什么要别名：重新索引（换分词器、改映射）必须在<b>不中断检索</b>的前提下完成。
     * 直接删了重建会出现"检索空窗期"，用别名切换则是一次原子操作。
     */
    void recreateIndex();

    /** 批量写入（按 chunkId 幂等 upsert）。 */
    void indexChunks(List<IndexedChunk> chunks);

    /** BM25 检索。 */
    List<ChunkSearchHit> search(Long knowledgeBaseId, String query, int topK);

    /** 按文档删除（文档删除/重新切分时调用）。 */
    void deleteByDocumentIds(List<Long> documentIds);

    /** 按知识库删除。 */
    void deleteByKnowledgeBase(Long knowledgeBaseId);

    /** 统计某知识库的文档数（对账用）。 */
    long countByKnowledgeBase(Long knowledgeBaseId);
}
