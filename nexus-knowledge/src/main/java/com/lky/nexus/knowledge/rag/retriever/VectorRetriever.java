package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.ai.rag.retriever.Retriever;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.retriever.ChunkHydrator.RecalledChunk;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 向量检索（pgvector）。
 *
 * <p>擅长"意思相近"：用户问"缓存被打穿了怎么办"，笔记里写的是"缓存击穿"，
 * 关键词一个字都对不上，向量能把它们连起来。
 *
 * <p>三个实现上的注意点：
 * <ol>
 *   <li><b>嵌入模型必须与知识库冻结的模型一致</b>：查询用模型 A、索引用模型 B，
 *       向量空间不一致，检索结果会错乱<b>而且不报错</b>。校验收敛在
 *       {@code KnowledgeEmbeddingResolver}，写入侧与读取侧共用同一份；</li>
 *   <li><b>召回深度大于返回条数</b>：融合前多取候选，否则"两路各自的第一名"
 *       可能因为一条不在另一路的候选里而被丢掉；</li>
 *   <li><b>相似度下限不能太紧</b>：太紧会静默漏掉相关内容（表现是"知识库里明明有却答不出来"），
 *       所以默认值取得偏宽松，宁可多召回交给重排处理。</li>
 * </ol>
 *
 * <p><b>元数据过滤为什么在这里先"解析"再下推</b>：pgvector 表只有 kb_id / document_id，
 * 标签与目录必须先由 {@link RetrievalFilterResolver} 在关系库里翻译成文档 id。
 * 这一步只在这一路做——关键词那一路由 ES 直接在索引侧按 tags/folder 过滤，
 * 混合检索时两路各调用一次，因此解析不会重复执行。
 */
@Component
public class VectorRetriever implements Retriever {

    /** 命中来源标识：与检索接口对外暴露的 `source` 字段取值一致，不要随意改。 */
    static final String SOURCE = "vector";

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeEmbeddingResolver embeddingResolver;
    private final VectorStore vectorStore;
    private final ChunkHydrator hydrator;
    private final RetrievalFilterResolver filterResolver;

    public VectorRetriever(KnowledgeBaseService knowledgeBaseService,
                           KnowledgeEmbeddingResolver embeddingResolver,
                           VectorStore vectorStore,
                           ChunkHydrator hydrator,
                           RetrievalFilterResolver filterResolver) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingResolver = embeddingResolver;
        this.vectorStore = vectorStore;
        this.hydrator = hydrator;
        this.filterResolver = filterResolver;
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public Set<RetrievalStrategy> strategies() {
        return Set.of(RetrievalStrategy.VECTOR);
    }

    @Override
    public List<RetrievedDocument> retrieve(RetrievalQuery query) {
        if (!query.hasQuery()) {
            return List.of();
        }
        List<List<RetrievedDocument>> perKnowledgeBase = new ArrayList<>(query.knowledgeBaseIds().size());
        for (Long knowledgeBaseId : query.knowledgeBaseIds()) {
            KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(query.userId(), knowledgeBaseId);
            RetrievalFilterResolver.FilterPlan plan = filterResolver.resolve(knowledgeBaseId, query.filters());
            if (plan.matchNothing()) {
                // 条件命中零篇：直接空结果。**绝不能**退化成"不带条件继续检索"——
                // 那会让用户按标签筛完之后拿到全库结果，且没有任何提示
                continue;
            }
            List<RecalledChunk> recalled = RecallSupport.ranked(
                    recall(knowledgeBase, query.query(), query.candidateDepth(),
                            query.effectiveSimilarityThreshold(), plan.documentIds()),
                    SOURCE, query.limit());
            perKnowledgeBase.add(hydrator.hydrate(knowledgeBaseId, recalled));
        }
        return RecallSupport.merge(perKnowledgeBase, query.limit());
    }

    /**
     * 只做召回，不回表（混合检索要用两路的原始排名做融合，不能各自先截断）。
     *
     * <p>返回的 {@code ChunkSearchHit} 补上来源标识是为了让它与关键词那一路
     * 统一成同一种形状，后续融合与回表都不用分情况处理。
     *
     * @param documentIds 已解析的文档范围；空表示不限（"命中零篇"已在调用方短路，不会传进来）
     */
    List<ChunkSearchHit> recall(KnowledgeBasePO knowledgeBase, String query, int depth, double minScore,
                                List<Long> documentIds) {
        EmbeddingClient client = embeddingResolver.resolve(knowledgeBase);
        float[] queryVector = client.embed(query);
        return vectorStore.search(knowledgeBase.getId(), queryVector, depth, minScore, documentIds).stream()
                .map(hit -> new ChunkSearchHit(hit.chunkId(), hit.score(), SOURCE))
                .toList();
    }
}
