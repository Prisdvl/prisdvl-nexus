package com.lky.nexus.knowledge.search;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;

import java.util.List;

/**
 * 检索索引端口（BM25 / 关键词检索）。
 *
 * <p>与 {@code VectorStore} 并列：向量检索擅长"意思相近"，关键词检索擅长
 * "精确术语、代码标识符、人名地名"。混合检索（Phase 3.6）就是把两者融合，
 * 因此这里刻意保持"只做 BM25"的单一职责，融合逻辑不渗进来。
 *
 * <p><b>为什么这里接收完整的 {@link RetrievalFilters}，而 {@code VectorStore} 只接收 documentIds</b>：
 * 端口应该只接受它<b>真的能兑现</b>的条件。ES 索引里 {@code tags} / {@code folder} /
 * {@code document_id} 都是 keyword/long 字段，三个条件都能在索引侧精确过滤（还能吃 filter cache）；
 * 而 pgvector 表只有 {@code kb_id} / {@code document_id} 两列，收到 tags 就只能忽略它——
 * 一个被忽略的过滤条件比没有这个条件糟得多：用户在混合检索里按标签筛，向量那一路
 * 悄悄返回了全库结果，最终结果是"筛了等于没筛"，且不报错。
 * 所以标签/目录在向量那一路必须先由上层解析成文档 id 再下推（见 {@code RetrievalFilterResolver}）。
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

    /**
     * BM25 检索（带元数据过滤）。
     *
     * <p>过滤条件的语义见 {@link RetrievalFilters}：三个条件 AND，标签内部 OR，
     * 目录包含子目录。<b>过滤必须下推到索引侧</b>，不能"先检索再在内存里筛"——
     * 后者会让 topK 的语义悄悄变成"筛之前的前 K 条"，本来相关的第 11 条永远回不来。
     */
    List<ChunkSearchHit> search(Long knowledgeBaseId, String query, int topK, RetrievalFilters filters);

    /**
     * 不带过滤的检索（等价于 {@link RetrievalFilters#none()}）。
     *
     * <p>保留成 default 而不是让调用方各自写 {@code none()}：这样"不过滤"只有一种写法，
     * 也不会因为某天给过滤加了默认值而让两个入口产生分歧。
     */
    default List<ChunkSearchHit> search(Long knowledgeBaseId, String query, int topK) {
        return search(knowledgeBaseId, query, topK, RetrievalFilters.none());
    }

    /** 按文档删除（文档删除/重新切分时调用）。 */
    void deleteByDocumentIds(List<Long> documentIds);

    /** 按知识库删除。 */
    void deleteByKnowledgeBase(Long knowledgeBaseId);

    /** 统计某知识库的文档数（对账用）。 */
    long countByKnowledgeBase(Long knowledgeBaseId);
}
