package com.lky.nexus.knowledge.service.impl;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.ai.rag.retriever.RetrieverRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.retriever.RetrievalQueryFactory;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索门面：把面向用户的 {@link SearchMode} 映射到内部检索策略，并转成对外的 {@code SearchHit}。
 *
 * <p><b>它现在只做三件事</b>：校验归属、把参数装成 {@link RetrievalQuery}、
 * 把 {@link RetrievedDocument} 转成接口约定的 {@code SearchHit}。真正的检索逻辑
 * （向量/关键词/融合/重排）都在 {@code com.lky.nexus.knowledge.rag.retriever} 下的
 * {@code Retriever} 实现里，由 {@link RetrieverRegistry} 派发。
 *
 * <p><b>为什么要留这一层而不是直接改调用方</b>：对外接口不变，意味着这次重构
 * 是"行为不变"的——五个调用点（检索接口、RAG 问答、Agent 工具、引用构建、上下文组装）
 * 一行都不用改，因此也就能用一个测试把"改前改后结果完全一致"证明出来。
 * 先证明没改坏，再谈加新能力，顺序不能反。
 *
 * <p><b>参数解析集中在 {@link RetrievalQueryFactory}</b>（Phase 3.2）：
 * 门面自己拿得到 {@link KnowledgeBasePO}，所以"调用方没指定就用知识库配置"
 * 这件事在这里一次解决，RAG 问答与 Agent 工具都不需要各自实现一遍——
 * 这也正是"知识库的 retrieval_top_k / similarity_threshold 真正生效"的落点。
 *
 * <p><b>保留 {@link SearchMode} 而不是直接暴露 {@link RetrievalStrategy}</b>：
 * 两者的受众不同。{@code SearchMode} 是用户能选的（bm25 / vector / hybrid 三选一），
 * {@code RetrievalStrategy} 是内部实现维度（还包含 {@code HYBRID_RERANK} 与未来的
 * GRAPH / SQL）。把内部枚举直接暴露给 API，等于把内部实现细节变成对外契约，
 * 以后每加一种策略都会变成一次接口变更。
 */
@Service
public class ChunkSearchServiceImpl implements ChunkSearchService {

    private static final Logger log = LoggerFactory.getLogger(ChunkSearchServiceImpl.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrieverRegistry retrieverRegistry;
    private final RetrievalQueryFactory queryFactory;

    public ChunkSearchServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                  RetrieverRegistry retrieverRegistry,
                                  RetrievalQueryFactory queryFactory) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.retrieverRegistry = retrieverRegistry;
        this.queryFactory = queryFactory;
    }

    @Override
    public List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, Integer topK, SearchMode mode) {
        return search(userId, knowledgeBaseId, query, topK, mode, RetrievalFilters.none());
    }

    @Override
    public List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, Integer topK, SearchMode mode,
                                  RetrievalFilters filters) {
        // 先校验归属再判空查询：顺序是刻意保持的——空查询 + 越权知识库
        // 必须仍然报"知识库不存在"，而不是悄悄返回空结果
        KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        if (query == null || query.isBlank()) {
            return List.of();
        }

        RetrievalStrategy strategy = toStrategy(mode);
        RetrievalFilters effectiveFilters = filters == null ? RetrievalFilters.none() : filters;
        RetrievalQuery retrievalQuery = queryFactory.forKnowledgeBase(
                knowledgeBase, query, null, topK, null, userId, strategy, effectiveFilters);
        // 把"实际生效的参数"打在召回**之前**：调参时最常见的问题是"我改了配置怎么没反应"，
        // 而只有解析后的值能回答它（请求传了 10 还是"没传"在日志里看不出区别）。
        // 放在前面还有一个原因：检索失败（ES/向量库不可用）时会直接抛异常，
        // 而那时的参数往往正是要排查的对象——日志留在失败之后，失败场景反而什么都没有。
        // 过滤条件同理：用户"筛了却还是全库结果"时，只有这行日志能说明条件到底有没有生效。
        log.info("检索「{}」mode={} strategy={} topK={} threshold={} filters={}（知识库 {} 的配置 {} / {}）",
                query, mode, strategy, retrievalQuery.limit(),
                retrievalQuery.effectiveSimilarityThreshold(), effectiveFilters.describe(), knowledgeBaseId,
                knowledgeBase.getRetrievalTopK(), knowledgeBase.getSimilarityThreshold());

        long start = System.currentTimeMillis();
        List<RetrievedDocument> documents = retrieverRegistry.get(strategy).retrieve(retrievalQuery);
        List<SearchHit> results = documents.stream().map(ChunkSearchServiceImpl::toSearchHit).toList();
        log.info("检索「{}」→ 返回 {} 条，耗时 {} ms", query, results.size(),
                System.currentTimeMillis() - start);
        return results;
    }

    /** 用户可见的检索模式 → 内部检索策略。 */
    private RetrievalStrategy toStrategy(SearchMode mode) {
        return switch (mode) {
            case BM25 -> RetrievalStrategy.KEYWORD;
            case VECTOR -> RetrievalStrategy.VECTOR;
            case HYBRID -> RetrievalStrategy.HYBRID;
        };
    }

    /**
     * 检索结果 → 接口模型。
     *
     * <p>{@code RetrievalStrategy} 那一层比 {@code SearchHit} 多出来的信息
     * （knowledgeBaseId / fileName / page）在这里丢掉是暂时的：对外契约保持不动，
     * 才能证明重构没有改变行为。等引用要定位到页码、多库检索要标明来源时，
     * 会把这些字段补进接口，而不是现在就顺手改掉。
     */
    private static SearchHit toSearchHit(RetrievedDocument document) {
        return new SearchHit(
                document.chunkId(),
                document.documentId(),
                document.documentTitle(),
                document.headingPath(),
                document.content(),
                document.score(),
                document.source(),
                document.matchedBy());
    }
}
