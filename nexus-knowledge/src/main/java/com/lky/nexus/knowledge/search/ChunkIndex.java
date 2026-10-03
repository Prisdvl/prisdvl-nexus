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

    /**
     * 确保指定知识库的索引与别名存在（幂等）。
     *
     * <p><b>为什么按知识库隔离</b>：索引的粒度必须与"谁会被重建"一致。曾经这里是
     * "所有知识库共用一个别名 + 一份物理索引"，于是重建 KB-A 会连带清空 KB-B/C 的
     * ES 文档，而 B/C 的切片在 MySQL 里仍是 {@code DONE}、不会重新索引——
     * <b>它们的 BM25 检索就此静默失效</b>（向量那一路还在召回，所以症状不是"搜不到"，
     * 而是"少了一路召回"，很难被发现）。
     * 一份物理索引对应一个知识库之后，"重建 A 只影响 A"是由结构保证的，
     * 不再依赖任何调用方记得去补偿。
     *
     * <p>别名规则见实现（{@code <base-alias>_<knowledgeBaseId>}）。别名而不是直接用
     * 物理索引名对外，是为了保留零点切换的能力——见 {@link #recreateIndex}。
     */
    void ensureIndex(Long knowledgeBaseId);

    /**
     * 重建<b>指定知识库</b>的索引：新建物理索引 → 原子切换该库的别名 → 删除旧索引。
     *
     * <p>为什么要别名：重新索引（换分词器、改映射）必须在<b>不中断检索</b>的前提下完成。
     * 直接删了重建会出现"检索空窗期"，用别名切换则是一次原子操作。
     *
     * <p>别名与物理索引都只属于这一个知识库，因此本方法<b>不触碰其它知识库</b>。
     */
    void recreateIndex(Long knowledgeBaseId);

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

    /**
     * 按文档删除（文档删除/重新切分时调用）。
     *
     * <p><b>这个方法没有 knowledgeBaseId</b>，因为调用点（文档删除）手里的就是一批文档 id，
     * 而知识库在索引侧是按库分片的。实现需要跨所有知识库的索引执行删除——
     * 这是"按库分片"换来的唯一代价，且删除是按 {@code document_id} 精确匹配，
     * 不存在误删别的库的风险。
     */
    void deleteByDocumentIds(List<Long> documentIds);

    /** 按知识库删除。 */
    void deleteByKnowledgeBase(Long knowledgeBaseId);

    /** 统计某知识库的文档数（对账用）。 */
    long countByKnowledgeBase(Long knowledgeBaseId);
}
