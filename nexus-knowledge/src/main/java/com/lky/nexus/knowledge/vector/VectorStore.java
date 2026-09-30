package com.lky.nexus.knowledge.vector;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 向量库端口。
 *
 * <p>领域层只依赖这个接口，具体实现（pgvector / 其它）由 app 层提供适配器——
 * 与消息队列同样的分层：<b>领域定义能力，基础设施提供实现</b>。
 * 好处是"换向量库"或"测试时替换"都不需要动业务代码，也让 nexus-knowledge 不依赖 JDBC 细节。
 */
public interface VectorStore {

    /**
     * 批量写入（幂等：同一个 chunkId 重复写入会覆盖）。
     *
     * <p>为什么必须幂等：MySQL 与 PostgreSQL 之间没有分布式事务。
     * 若"写入向量成功、更新 MySQL 状态失败"，重试时就会重复写同一批——
     * 用 {@code ON CONFLICT (chunk_id) DO UPDATE} 让重试天然安全。
     */
    void upsert(List<VectorRecord> records);

    /**
     * 向量检索。
     *
     * @param knowledgeBaseId 知识库 id（租户/库内过滤，绝不能跨库检索）
     * @param queryEmbedding  查询向量
     * @param topK            返回条数
     * @param minScore        最低相似度（过滤明显不相关的结果）
     * @param documentIds     限定文档范围；为空表示不限定。<b>语义是"只在这些文档里找"，
     *                        而不是"顺带过滤"</b>——空集合必须解释成"没有限定"，
     *                        因此调用方想要表达"匹配到零篇文档"时，应当直接不调用本方法
     *                        （返回空结果），而不是传一个空集合进来（那会变成全库检索）。
     */
    List<VectorSearchHit> search(Long knowledgeBaseId, float[] queryEmbedding, int topK, double minScore,
                                 Collection<Long> documentIds);

    /** 不限定文档范围的检索。 */
    default List<VectorSearchHit> search(Long knowledgeBaseId, float[] queryEmbedding, int topK,
                                         double minScore) {
        return search(knowledgeBaseId, queryEmbedding, topK, minScore, null);
    }

    /** 按内容哈希批量查缓存（命中则无需再调用嵌入模型）。 */
    Map<String, float[]> findCachedEmbeddings(Collection<String> contentHashes);

    /** 写入嵌入缓存。 */
    void cacheEmbeddings(Map<String, float[]> embeddingsByHash, String model);

    /** 按文档删除向量（文档删除/重新切分时调用）。 */
    void deleteByDocumentIds(Collection<Long> documentIds);

    /**
     * 按知识库删除向量（删除知识库时调用）。
     *
     * <p>少了这一步就会留下"孤儿向量"：MySQL 里的切片已经没了，
     * 但向量库里还在，检索时命中却查不到内容，表现为"结果里出现空白条目"。
     */
    void deleteByKnowledgeBase(Long knowledgeBaseId);

    /** 统计某知识库的向量条数（用于对账：应与 kb_chunk.vector_status='DONE' 的数量一致）。 */
    long countByKnowledgeBase(Long knowledgeBaseId);
}
