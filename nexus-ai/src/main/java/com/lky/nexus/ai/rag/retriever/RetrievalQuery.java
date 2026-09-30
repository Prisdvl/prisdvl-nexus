package com.lky.nexus.ai.rag.retriever;

import java.util.List;

/**
 * 统一检索查询对象。
 *
 * <p>之前检索的参数是散落在调用点的位置参数（{@code search(userId, kbId, query, topK, mode)}），
 * 每加一个维度（过滤条件、阈值、重排、多库）就要改一次签名，所有调用点跟着改。
 * 收敛成一个查询对象后，<b>新增维度不再牵动调用方</b>，也让"这次检索到底按什么条件做的"
 * 可以被完整记录、回放、进评测集。
 *
 * <p>三个默认值与上限刻意放在这里，而不是散在各个 Retriever 实现里：
 * 它们是<b>检索契约的一部分</b>（"topK 超过 100 一律按 100 算"），
 * 放在每个实现里迟早会出现两路不一致。
 *
 * <p>关于 {@code similarityThreshold}：这是"要不要把这条召回结果留下"的下限。
 * 太松（0.1）会让无关片段进入上下文、稀释重排；太紧（0.8）会直接漏掉相关内容且不报错。
 * 当前的 {@link #DEFAULT_SIMILARITY_THRESHOLD} 是 bge-m3 在中文短查询上的经验值，
 * 真正的取值要用检索评测数据来定。
 */
public record RetrievalQuery(
        /** 召回用查询语句。追问场景下应当是"消解指代后"的查询（见 RagAnswerService）。 */
        String query,
        /** 重排用查询语句（用户原问题）；为 null 时退回用 {@link #query}。 */
        String rerankQuery,
        /** 调用者身份：所有检索都必须显式带上，禁止在实现里读 SecurityContext。 */
        Long userId,
        /** 工作区 id：资源隔离维度，见 Phase 3 的 Workspace 隔离设计。 */
        Long workspaceId,
        /** 目标知识库。当前检索链路是单库，列表化是为了让"跨库检索"不必再改签名。 */
        List<Long> knowledgeBaseIds,
        /** 期望返回条数（会被 {@link #limit()} 夹到 [1, MAX_TOP_K]）。 */
        int topK,
        /** 向量相似度下限；<= 0 时用 {@link #DEFAULT_SIMILARITY_THRESHOLD}。 */
        double similarityThreshold,
        /** 元数据过滤条件。 */
        RetrievalFilters filters,
        /** 检索策略（由 {@link RetrieverRegistry} 派发到具体实现）。 */
        RetrievalStrategy strategy) {

    /** 单次检索返回条数上限：再大也没有意义，只会拖慢链路。 */
    public static final int MAX_TOP_K = 100;

    /**
     * 返回条数的默认值。
     *
     * <p>只作为 Java 侧兜底；线上真正的默认值来源是 {@link RetrievalDefaults}
     * （{@code nexus.retrieval.top-k}），而它的初值又指向这里，因此不会各改一半。
     */
    public static final int DEFAULT_TOP_K = 5;

    /** 单路召回的候选数上限：太深会把 RRF 稀释成"谁候选多谁赢"。 */
    public static final int MAX_CANDIDATES = 200;

    /**
     * 召回深度相对返回条数的倍数。
     *
     * <p>必须大于 1：融合前每路多取候选，否则"两路各自的第一名"可能
     * 因为一条不在另一路的候选里而被丢掉。
     */
    public static final int RECALL_MULTIPLIER = 3;

    /** 向量相似度下限的默认值（见类注释）。 */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.25;

    /** 归一化：null 集合/条件收敛成安全值，策略缺省为 {@link RetrievalStrategy#DEFAULT}。 */
    public RetrievalQuery {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        filters = filters == null ? RetrievalFilters.none() : filters;
        strategy = strategy == null ? RetrievalStrategy.DEFAULT : strategy;
    }

    /**
     * 单库检索（当前唯一在用的形态）。
     *
     * <p>注意 {@code rerankQuery} 传 null：重排用与召回相同的查询语句。
     */
    public static RetrievalQuery of(String query, Long userId, Long workspaceId, Long knowledgeBaseId,
                                    int topK, RetrievalStrategy strategy) {
        return new RetrievalQuery(query, null, userId, workspaceId, List.of(knowledgeBaseId),
                topK, DEFAULT_SIMILARITY_THRESHOLD, RetrievalFilters.none(), strategy);
    }

    /** 实际生效的返回条数。 */
    public int limit() {
        return Math.clamp(topK, 1, MAX_TOP_K);
    }

    /** 实际生效的召回深度。 */
    public int candidateDepth() {
        return Math.min(MAX_CANDIDATES, limit() * RECALL_MULTIPLIER);
    }

    /** 实际生效的相似度下限。 */
    public double effectiveSimilarityThreshold() {
        return similarityThreshold > 0 ? similarityThreshold : DEFAULT_SIMILARITY_THRESHOLD;
    }

    /** 重排实际使用的查询语句（未单独指定时退回召回语句）。 */
    public String effectiveRerankQuery() {
        return rerankQuery == null || rerankQuery.isBlank() ? query : rerankQuery;
    }

    /** 有没有可检索的内容（空查询不该走到召回层）。 */
    public boolean hasQuery() {
        return query != null && !query.isBlank();
    }
}
