package com.lky.nexus.app.infra.vector;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.vector.VectorRecord;
import com.lky.nexus.knowledge.vector.VectorSearchHit;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 向量库未启用时的降级实现。
 *
 * <p>用于 standalone profile 与单元测试（环境里没有 PostgreSQL）。
 *
 * <p><b>为什么是"明确报错"而不是"静默空实现"</b>：如果这里安静地什么都不做，
 * 表现会是"导入成功、检索永远返回空"，排查成本极高。
 * 让它在被使用时直接抛 4003（向量库不可用）并说明原因，
 * 使用者立刻知道"当前环境没有向量库，检索功能不可用"。
 */
@Component
@ConditionalOnProperty(name = "nexus.vector.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(DisabledVectorStore.class);

    private static final String MESSAGE =
            "向量库未启用（nexus.vector.enabled=false）：当前环境没有 PostgreSQL/pgvector，"
                    + "向量写入与检索不可用。请启动基础设施（deploy/docker-compose.yml）并开启该配置。";

    public DisabledVectorStore() {
        log.warn("向量库未启用：写入与检索会明确报错，而不是静默失败（{}）", MESSAGE);
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE, MESSAGE);
    }

    @Override
    public List<VectorSearchHit> search(Long knowledgeBaseId, float[] queryEmbedding,
                                        int topK, double minScore) {
        throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE, MESSAGE);
    }

    @Override
    public Map<String, float[]> findCachedEmbeddings(Collection<String> contentHashes) {
        // 缓存查询可以安全地"没有"：它只影响是否重复调用嵌入模型
        return Map.of();
    }

    @Override
    public void cacheEmbeddings(Map<String, float[]> embeddingsByHash, String model) {
        // 同上：缓存写不进去不影响正确性
    }

    @Override
    public void deleteByDocumentIds(Collection<Long> documentIds) {
        // 没有向量库就没有向量要删
    }

    @Override
    public long countByKnowledgeBase(Long knowledgeBaseId) {
        return 0;
    }
}
