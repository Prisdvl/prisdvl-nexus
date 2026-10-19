package com.lky.nexus.app.infra.search;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.IndexedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检索索引未启用时的降级实现。
 *
 * <p>与 {@code DisabledVectorStore} 同样的原则：**明确报错，而不是静默什么都不做**。
 * 静默的后果是"导入成功、检索永远为空"，排查成本高得多。
 */
@Component
@ConditionalOnProperty(name = "nexus.search.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledChunkIndex implements ChunkIndex {

    private static final Logger log = LoggerFactory.getLogger(DisabledChunkIndex.class);

    private static final String MESSAGE =
            "检索索引未启用（nexus.search.enabled=false）：当前环境没有 Elasticsearch，"
                    + "BM25 索引与关键词检索不可用。请启动基础设施（deploy/docker-compose.yml）并开启该配置。";

    public DisabledChunkIndex() {
        log.warn("检索索引未启用：写入与检索会明确报错（{}）", MESSAGE);
    }

    @Override
    public void ensureIndex(Long knowledgeBaseId) {
        throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE, MESSAGE);
    }

    @Override
    public void recreateIndex(Long knowledgeBaseId) {
        throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE, MESSAGE);
    }

    @Override
    public void indexChunks(List<IndexedChunk> chunks) {
        throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE, MESSAGE);
    }

    @Override
    public List<ChunkSearchHit> search(Long knowledgeBaseId, String query, int topK,
                                       RetrievalFilters filters) {
        throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE, MESSAGE);
    }

    @Override
    public void deleteByDocumentIds(List<Long> documentIds) {
        // 没有索引就没有要删的内容
    }

    @Override
    public void deleteByKnowledgeBase(Long knowledgeBaseId) {
        // 同上
    }

    @Override
    public void deleteIndexFor(Long knowledgeBaseId) {
        // 没有索引就没有要删的结构（与"未启用时写入明确报错"不冲突：删除是清理动作，
        // 让删库在没开检索索引的环境里也能走完，比抛错更有用）
    }

    @Override
    public long countByKnowledgeBase(Long knowledgeBaseId) {
        return 0;
    }
}
