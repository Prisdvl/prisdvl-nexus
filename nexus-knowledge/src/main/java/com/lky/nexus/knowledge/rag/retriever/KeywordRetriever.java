package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.ai.rag.retriever.Retriever;
import com.lky.nexus.knowledge.rag.retriever.ChunkHydrator.RecalledChunk;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 关键词检索（Elasticsearch + IK 中文分词，BM25）。
 *
 * <p>擅长"精确术语"：代码标识符（{@code HCS_E_HYPERV_NOT_INSTALLED}）、
 * 人名地名、型号、报错原文——这些恰恰是向量检索最不擅长的地方
 * （嵌入模型会把它们抹成"差不多意思"）。两路并存不是为了保险，
 * 是因为它们各自覆盖的查询类型确实不同。
 *
 * <p>元数据过滤（Phase 3.3）：条件原样交给 {@link ChunkIndex} 在索引侧过滤。
 * 索引里 {@code tags} / {@code folder} / {@code document_id} 都是 keyword/long 字段，
 * 三个条件都能精确下推，不需要先查关系库——这也是"关键词那一路比向量那一路省一次查询"的原因。
 */
@Component
public class KeywordRetriever implements Retriever {

    /** 命中来源标识：与检索接口对外暴露的 `source` 字段取值一致，不要随意改。 */
    static final String SOURCE = "bm25";

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkIndex chunkIndex;
    private final ChunkHydrator hydrator;

    public KeywordRetriever(KnowledgeBaseService knowledgeBaseService,
                            ChunkIndex chunkIndex,
                            ChunkHydrator hydrator) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkIndex = chunkIndex;
        this.hydrator = hydrator;
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public Set<RetrievalStrategy> strategies() {
        return Set.of(RetrievalStrategy.KEYWORD);
    }

    @Override
    public List<RetrievedDocument> retrieve(RetrievalQuery query) {
        if (!query.hasQuery()) {
            return List.of();
        }
        List<List<RetrievedDocument>> perKnowledgeBase = new ArrayList<>(query.knowledgeBaseIds().size());
        for (Long knowledgeBaseId : query.knowledgeBaseIds()) {
            knowledgeBaseService.requireOwned(query.userId(), knowledgeBaseId);
            List<RecalledChunk> recalled = RecallSupport.ranked(
                    recall(knowledgeBaseId, query.query(), query.candidateDepth(), query.filters()),
                    SOURCE, query.limit());
            perKnowledgeBase.add(hydrator.hydrate(knowledgeBaseId, recalled));
        }
        return RecallSupport.merge(perKnowledgeBase, query.limit());
    }

    /** 只做召回，不回表（混合检索用原始排名做融合）。 */
    List<ChunkSearchHit> recall(Long knowledgeBaseId, String query, int depth, RetrievalFilters filters) {
        return chunkIndex.search(knowledgeBaseId, query, depth, filters);
    }
}
