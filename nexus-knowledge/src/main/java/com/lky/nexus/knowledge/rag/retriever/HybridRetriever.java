package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.ai.rag.retriever.Retriever;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.retriever.ChunkHydrator.RecalledChunk;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 混合检索：关键词 + 向量两路召回，用 RRF 融合。
 *
 * <p>流程：
 * <pre>
 *   query ─┬─ 关键词（ES/IK） ──────┐
 *          └─ 嵌入 → pgvector ────┴─ RRF 融合 ─ 回表补齐正文/标题 ─ 结果
 * </pre>
 *
 * <p><b>它不直接持有 ES 与 pgvector</b>，只调用另外两个 Retriever 的召回方法——
 * 这样"怎么从 ES 取"和"怎么从向量库取"各自只有一份实现，
 * 融合层不需要知道任何一个基础设施的细节。这也让"新增一路召回"变成
 * 加一个实现 + 在这里多接一路，而不是在融合代码里加分支。
 */
@Component
public class HybridRetriever implements Retriever {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final ChunkHydrator hydrator;
    private final RetrievalFilterResolver filterResolver;

    public HybridRetriever(KnowledgeBaseService knowledgeBaseService,
                           VectorRetriever vectorRetriever,
                           KeywordRetriever keywordRetriever,
                           ChunkHydrator hydrator,
                           RetrievalFilterResolver filterResolver) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.hydrator = hydrator;
        this.filterResolver = filterResolver;
    }

    @Override
    public String name() {
        return "hybrid";
    }

    @Override
    public Set<RetrievalStrategy> strategies() {
        return Set.of(RetrievalStrategy.HYBRID);
    }

    @Override
    public List<RetrievedDocument> retrieve(RetrievalQuery query) {
        if (!query.hasQuery()) {
            return List.of();
        }
        // 召回深度 > 返回条数：先宽后窄。两路都要多取，
        // 否则"两路各自的第一名"可能因为一条不在另一路的候选里而被丢掉
        int depth = query.candidateDepth();
        List<List<RetrievedDocument>> perKnowledgeBase = new ArrayList<>(query.knowledgeBaseIds().size());
        for (Long knowledgeBaseId : query.knowledgeBaseIds()) {
            KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(query.userId(), knowledgeBaseId);
            // 过滤条件在**融合之前**下推到两路：先各自筛完再融合，
            // 否则被筛掉的候选已经占掉了各自的名次，融合出来的排名与"只在这个标签下找"对不上
            RetrievalFilterResolver.FilterPlan plan = filterResolver.resolve(knowledgeBaseId, query.filters());
            if (plan.matchNothing()) {
                // 命中零篇：两路都空，直接跳过这个库（不是"不过滤"）
                continue;
            }
            List<RecalledChunk> fused = RecallSupport.fuse(
                    keywordRetriever.recall(knowledgeBaseId, query.query(), depth, query.filters()),
                    vectorRetriever.recall(knowledgeBase, query.query(), depth,
                            query.effectiveSimilarityThreshold(), plan.documentIds()),
                    query.limit());
            perKnowledgeBase.add(hydrator.hydrate(knowledgeBaseId, fused));
        }
        return RecallSupport.merge(perKnowledgeBase, query.limit());
    }
}
