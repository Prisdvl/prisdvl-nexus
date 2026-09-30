package com.lky.nexus.app.infra.vector;

import com.lky.nexus.knowledge.vector.VectorRecord;
import com.lky.nexus.knowledge.vector.VectorSearchHit;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * pgvector 适配器：手写 SQL 而不是套框架的向量库抽象。
 *
 * <p>理由：向量检索的核心就是一条 SQL（余弦距离 + ANN 索引 + 过滤），
 * 把它藏进框架抽象后，出了问题（召回率不对、索引没走、维度不匹配）根本无从查起。
 * 这里每条 SQL 都摆在明面上，也让"混合检索怎么融合"这类决策留在自己手里。
 *
 * <p>三个实现要点：
 * <ol>
 *   <li><b>向量以文本字面量传入</b>（{@code '[0.1,0.2,...]'::vector}）：pgvector 的输入格式就是如此，
 *       JDBC 没有原生 vector 类型，这样写最简单也最快；</li>
 *   <li><b>写入用 {@code ON CONFLICT (chunk_id) DO UPDATE}</b>：MySQL 与 PG 之间没有分布式事务，
 *       "向量写成功、状态更新失败"时重试必须安全；</li>
 *   <li><b>检索必须带 {@code kb_id} 过滤</b>：否则会把别的知识库（甚至别的用户）的切片检索出来。</li>
 * </ol>
 *
 * <p><b>已知的召回率细节</b>：pgvector 的 HNSW 索引 + WHERE 过滤时，索引扫描可能先取到
 * 一批近邻、过滤后不足 topK（表现为"我明明有一万条，却只返回 3 条"）。
 * pgvector 0.8 提供了 {@code hnsw.iterative_scan} 缓解，本项目把这一步留到
 * Phase 3 的检索评测里调（届时用真实数据衡量召回与延迟的取舍），而不是现在拍脑袋设参数。
 */
@Repository
@ConditionalOnProperty(name = "nexus.vector.enabled", havingValue = "true")
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO kb_vector (chunk_id, kb_id, document_id, content_hash, embedding)
                VALUES (?, ?, ?, ?, CAST(? AS vector))
                ON CONFLICT (chunk_id) DO UPDATE
                    SET kb_id = EXCLUDED.kb_id,
                        document_id = EXCLUDED.document_id,
                        content_hash = EXCLUDED.content_hash,
                        embedding = EXCLUDED.embedding
                """, records, records.size(), (statement, record) -> {
            statement.setLong(1, record.chunkId());
            statement.setLong(2, record.kbId());
            statement.setLong(3, record.documentId());
            statement.setString(4, record.contentHash());
            statement.setString(5, toVectorLiteral(record.embedding()));
        });
        log.debug("写入向量 {} 条", records.size());
    }

    @Override
    public List<VectorSearchHit> search(Long knowledgeBaseId, float[] queryEmbedding, int topK,
                                        double minScore, Collection<Long> documentIds) {
        SearchStatement statement = buildSearchStatement(
                knowledgeBaseId, toVectorLiteral(queryEmbedding), topK, documentIds);
        List<VectorSearchHit> hits = jdbcTemplate.query(statement.sql(),
                (rs, rowNum) -> new VectorSearchHit(rs.getLong("chunk_id"), rs.getDouble("score")),
                statement.arguments());

        List<VectorSearchHit> filtered = hits.stream().filter(hit -> hit.score() >= minScore).toList();
        if (filtered.size() < hits.size()) {
            log.debug("向量检索命中 {} 条，低于相似度阈值 {} 被过滤掉 {} 条",
                    hits.size(), minScore, hits.size() - filtered.size());
        }
        return filtered;
    }

    /**
     * 组装检索语句（SQL + 按序参数）。
     *
     * <p>把 SQL 拼装从执行里拆出来，是为了让它可被验证：本地没有 pgvector
     * （Docker/WSL2 不可用），真机检索只能标 UNAVAILABLE，但"限定文档范围时
     * 到底发了哪条 SQL、参数顺序对不对"是纯字符串与数组的问题，可以脱离数据库断言。
     * 参数顺序尤其值得钉住——{@code CAST(? AS vector)} 在这条语句里出现两次
     * （算分一次、排序一次），插错位置不会报错，只会静默返回错误结果。
     *
     * <p>{@code document_id} 的限定直接写进 WHERE：pgvector 的 HNSW 索引 + 过滤
     * 会先取近邻再过滤，可能返回不足 topK 条（见类注释里的已知召回率细节），
     * 但"先检索再在内存里按文档筛"更糟——那会把 topK 变成"筛之前的前 K 条"。
     */
    static SearchStatement buildSearchStatement(Long knowledgeBaseId, String literal, int topK,
                                                Collection<Long> documentIds) {
        List<Long> ids = documentIds == null ? List.of() : List.copyOf(documentIds);
        StringBuilder sql = new StringBuilder("""
                SELECT chunk_id, 1 - (embedding <=> CAST(? AS vector)) AS score
                FROM kb_vector
                WHERE kb_id = ?
                """);
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(literal);
        arguments.add(knowledgeBaseId);
        if (!ids.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            sql.append("  AND document_id IN (").append(placeholders).append(")\n");
            arguments.addAll(ids);
        }
        sql.append("""
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """);
        arguments.add(literal);
        arguments.add(topK);
        return new SearchStatement(sql.toString(), arguments.toArray());
    }

    /** 一条可执行的检索语句：SQL 文本 + 与占位符一一对应的参数。 */
    record SearchStatement(String sql, Object[] arguments) {
    }

    @Override
    public Map<String, float[]> findCachedEmbeddings(Collection<String> contentHashes) {
        if (contentHashes == null || contentHashes.isEmpty()) {
            return Map.of();
        }
        List<String> hashes = List.copyOf(contentHashes);
        // 用 IN 列表查询；切片数量级在万级，分批由调用方控制（见 ChunkEmbeddingServiceImpl）
        String placeholders = String.join(",", java.util.Collections.nCopies(hashes.size(), "?"));
        Map<String, float[]> result = new HashMap<>();
        jdbcTemplate.query("""
                SELECT content_hash, embedding::text AS embedding_text
                FROM embedding_cache
                WHERE content_hash IN (%s)
                """.formatted(placeholders), rs -> {
            result.put(rs.getString("content_hash"), fromVectorLiteral(rs.getString("embedding_text")));
        }, hashes.toArray());
        return result;
    }

    @Override
    public void cacheEmbeddings(Map<String, float[]> embeddingsByHash, String model) {
        if (embeddingsByHash == null || embeddingsByHash.isEmpty()) {
            return;
        }
        List<Map.Entry<String, float[]>> entries = List.copyOf(embeddingsByHash.entrySet());
        jdbcTemplate.batchUpdate("""
                INSERT INTO embedding_cache (content_hash, model, embedding)
                VALUES (?, ?, CAST(? AS vector))
                ON CONFLICT (content_hash) DO NOTHING
                """, entries, entries.size(), (statement, entry) -> {
            statement.setString(1, entry.getKey());
            statement.setString(2, model);
            statement.setString(3, toVectorLiteral(entry.getValue()));
        });
    }

    @Override
    public void deleteByDocumentIds(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        List<Long> ids = List.copyOf(documentIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        int deleted = jdbcTemplate.update(
                "DELETE FROM kb_vector WHERE document_id IN (%s)".formatted(placeholders), ids.toArray());
        log.debug("按文档删除向量 {} 条", deleted);
    }

    @Override
    public void deleteByKnowledgeBase(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        int deleted = jdbcTemplate.update("DELETE FROM kb_vector WHERE kb_id = ?", knowledgeBaseId);
        log.debug("按知识库删除向量 {} 条（kbId={}）", deleted, knowledgeBaseId);
    }

    @Override
    public long countByKnowledgeBase(Long knowledgeBaseId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_vector WHERE kb_id = ?", Long.class, knowledgeBaseId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------

    /** 转 pgvector 的文本输入格式：{@code [0.1,0.2,...]}。 */
    static String toVectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 8).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }

    /** 解析 pgvector 的输出文本（{@code [0.1,0.2,...]}）为 float 数组。 */
    static float[] fromVectorLiteral(String literal) {
        String trimmed = literal.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return new float[0];
        }
        String[] parts = trimmed.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }
}
