package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.ChunkSearchHit;

import java.util.List;

/**
 * 单路「纯召回」入口，只服务于 {@code /api/search/explain}。
 *
 * <p><b>为什么需要这一层</b>：{@link KeywordRetriever#recall} 与
 * {@link VectorRetriever#recall} 是包内可见的（它们不该被外部按库调用，否则会绕开
 * 归属校验与过滤解析）。explain 需要的是"两路各来一份原始结果"，而不是融合后的结论，
 * 直接在包里加这个薄接口，比把 recall 改成 public 更安全，也让测试能替换掉真正打 ES/pgvector 的那一步。
 *
 * <p><b>只读约定</b>：实现不得写入任何东西——explain 是调参工具，跑一次不该留下痕迹。
 */
public interface ExplainRecaller {

    /**
     * 关键词路（BM25）原始召回。
     *
     * @param knowledgeBaseId 目标库（调用方已完成归属校验）
     * @param query           检索词
     * @param depth           每路召回深度（由 {@code RetrievalQuery.candidateDepth()} 决定）
     * @param filters         元数据过滤；{@code null} 视为不过滤
     * @return 按 BM25 分数降序的命中
     */
    List<ChunkSearchHit> keywordHits(Long knowledgeBaseId, String query, int depth, RetrievalFilters filters);

    /**
     * 向量路原始召回。
     *
     * @param knowledgeBase 目标库
     * @param query         检索词
     * @param depth         每路召回深度
     * @param minScore      相似度下限（取库自己的阈值）
     * @param documentIds   文档范围；{@code null} 或空表示不限
     * @return 按相似度降序的命中
     */
    List<ChunkSearchHit> vectorHits(KnowledgeBasePO knowledgeBase, String query, int depth,
                                    double minScore, List<Long> documentIds);
}
