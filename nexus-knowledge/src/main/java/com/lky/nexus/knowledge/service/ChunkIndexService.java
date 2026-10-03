package com.lky.nexus.knowledge.service;

/**
 * 切片索引服务：把 MySQL 里的切片写入 Elasticsearch（BM25 侧）。
 *
 * <p>与向量化并列的第二条"写索引"链路。两者分开而不是合成一件事：
 * <ul>
 *   <li>依赖的中间件不同（ES vs pgvector），失败原因与排查方向完全不同；</li>
 *   <li>可以各自重建（换分词器只需重建 ES，换嵌入模型只需重建向量）。</li>
 * </ul>
 * 共同点是都用 {@code kb_chunk} 里的状态列（{@code es_status} / {@code vector_status}）
 * 驱动，天然幂等、可重跑。
 */
public interface ChunkIndexService {

    /**
     * 为知识库中待索引的切片建立检索索引。
     *
     * @param userId     归属用户
     * @param knowledgeBaseId 知识库 id
     * @param batchLimit 单次处理的切片上限
     */
    IndexResult indexPendingChunks(Long userId, Long knowledgeBaseId, int batchLimit);

    /**
     * 重建<b>该知识库</b>的索引（别名原子切换，检索不中断）。
     *
     * <p>作用域严格限定在这一个知识库：其它知识库的索引与切片状态都不受影响。
     * 它们各自有独立的物理索引与别名，重建这一个碰不到它们。
     */
    void recreateIndex(Long userId, Long knowledgeBaseId);

    /** 删除若干文档的索引（文档被删除/重新切分时调用）。 */
    void deleteByDocumentIds(java.util.Collection<Long> documentIds);

    /**
     * @param total    本次取出的待索引切片数
     * @param indexed  成功写入 ES 的条数
     * @param costMillis 耗时
     */
    record IndexResult(int total, int indexed, long costMillis) {
    }
}
