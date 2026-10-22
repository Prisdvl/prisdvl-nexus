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
     * 该知识库的索引覆盖情况：一共多少切片、其中多少已进索引、最近一次写入的时间。
     *
     * <p><b>为什么单独一个方法</b>：这两个数决定的是"检索为 0 命中时该怎么解释给用户"，
     * 而不是"索引写得好不好"。放在 {@link #indexPendingChunks} 的返回值里只能回答后者，
     * 而且只有刚跑过建索引的人拿得到——真正需要它的是<b>没跑过</b>的那位用户。
     */
    IndexCoverage coverage(Long userId, Long knowledgeBaseId);

    /**
     * @param total    本次取出的待索引切片数
     * @param indexed  成功写入 ES 的条数
     * @param costMillis 耗时
     */
    record IndexResult(int total, int indexed, long costMillis) {
    }

    /**
     * 索引覆盖快照。
     *
     * @param chunkCount      切片总数（有内容才有得索引）
     * @param indexedChunkCount 已写入索引的切片数（{@code es_status = 'DONE'}）
     * @param lastIndexedAt   最近一次写入索引的时间；从未建过索引时是 {@code null}
     */
    record IndexCoverage(long chunkCount, long indexedChunkCount, java.time.LocalDateTime lastIndexedAt) {

        /** 有切片但一条都没进索引：这个库现在检索不到任何东西，且换关键词也没用。 */
        public boolean notIndexed() {
            return chunkCount > 0 && indexedChunkCount == 0;
        }

        /** 一条切片都没有：库是空的，谈不上"没建索引"。 */
        public boolean empty() {
            return chunkCount == 0;
        }
    }
}
