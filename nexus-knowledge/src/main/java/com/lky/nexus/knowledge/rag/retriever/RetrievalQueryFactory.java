package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把"调用方要什么 / 知识库配了什么 / 全局默认是什么"合并成一个 {@link RetrievalQuery}。
 *
 * <p>这是检索参数的<b>唯一解析入口</b>。优先级（自高到低）：
 * <pre>
 * 调用方显式指定  &gt;  知识库自身配置（retrieval_top_k / similarity_threshold）  &gt;  {@link RetrievalDefaults}
 * </pre>
 *
 * <p><b>为什么必须有这一层</b>：在 Phase 3.2 之前，知识库表里的
 * {@code retrieval_top_k} 与 {@code similarity_threshold} 两列<b>从来没有被读过</b>——
 * 界面上能改、接口里能返回，但检索时用的始终是代码里写死的值
 * （建库默认 0.7 与检索实际用的 0.25 互不知情）。"能配置"和"配置生效"是两件事，
 * 这个类负责把它们接起来；同时它也是唯一说出"这次检索按什么参数做的"的地方，
 * 因此调用方可以直接把它解析出的值写进日志。
 *
 * <p>越界值一律夹取（{@code clamp}）而不是抛错：参数离谱不该变成"功能不可用"。
 */
@Component
public class RetrievalQueryFactory {

    private final RetrievalDefaults defaults;

    public RetrievalQueryFactory(RetrievalDefaults defaults) {
        this.defaults = defaults;
    }

    /**
     * 解析实际生效的返回条数。
     *
     * @param requestedTopK 调用方显式指定的条数；null = 没指定，用知识库配置
     * @param knowledgeBase 目标知识库；null = 拿不到配置（例如只有 id 的场景），用全局默认
     */
    public int resolveTopK(Integer requestedTopK, KnowledgeBasePO knowledgeBase) {
        if (requestedTopK != null) {
            return Math.clamp(requestedTopK, 1, RetrievalQuery.MAX_TOP_K);
        }
        Integer configured = knowledgeBase == null ? null : knowledgeBase.getRetrievalTopK();
        if (configured != null) {
            return Math.clamp(configured, 1, RetrievalQuery.MAX_TOP_K);
        }
        return Math.clamp(defaults.getTopK(), 1, RetrievalQuery.MAX_TOP_K);
    }

    /**
     * 解析实际生效的向量相似度下限。
     *
     * <p>null 与"非正数"都算<b>未指定</b>：0 表示"不设下限"这种用法在本项目里没有语义
     * （向量检索总需要一个噪声下限，否则会把一堆无关片段灌进上下文），
     * 而 {@link RetrievalQuery#effectiveSimilarityThreshold()} 里也是同一条约定。
     * 真正想放松阈值，请把一个很小的正数（例如 0.05）写进知识库配置——那样它才是被显式选择过的。
     */
    public double resolveThreshold(Double requested, KnowledgeBasePO knowledgeBase) {
        Double candidate = requested != null ? requested : (knowledgeBase == null ? null : knowledgeBase.getSimilarityThreshold());
        if (candidate != null && candidate > 0) {
            return candidate;
        }
        return defaults.getSimilarityThreshold() > 0
                ? defaults.getSimilarityThreshold()
                : RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD;
    }

    /**
     * 单库检索的查询对象（当前唯一在用的形态）。
     *
     * @param rerankQuery 重排用语句（用户原问题）；为 null 时重排退回用 {@code query}
     */
    public RetrievalQuery forKnowledgeBase(KnowledgeBasePO knowledgeBase, String query, String rerankQuery,
                                           Integer requestedTopK, Double requestedThreshold,
                                           Long userId, RetrievalStrategy strategy) {
        return forKnowledgeBase(knowledgeBase, query, rerankQuery, requestedTopK, requestedThreshold,
                userId, strategy, RetrievalFilters.none());
    }

    /**
     * 单库检索的查询对象（带元数据过滤）。
     *
     * <p>过滤条件在这里只做<b>透传</b>：它需要的"标签 → 文档 id"翻译是数据库查询，
     * 属于 {@link RetrievalFilterResolver} 的职责；参数解析（条数/阈值）与条件解析
     * 分开的理由是——前者每个调用点都要算，后者只有真正下推的那一路才算。
     *
     * @param filters 标签 / 目录 / 指定文档；null 与 {@link RetrievalFilters#none()} 等价
     */
    public RetrievalQuery forKnowledgeBase(KnowledgeBasePO knowledgeBase, String query, String rerankQuery,
                                           Integer requestedTopK, Double requestedThreshold,
                                           Long userId, RetrievalStrategy strategy,
                                           RetrievalFilters filters) {
        return new RetrievalQuery(
                query,
                rerankQuery,
                userId,
                knowledgeBase == null ? null : knowledgeBase.getWorkspaceId(),
                knowledgeBase == null ? List.of() : List.of(knowledgeBase.getId()),
                resolveTopK(requestedTopK, knowledgeBase),
                resolveThreshold(requestedThreshold, knowledgeBase),
                filters == null ? RetrievalFilters.none() : filters,
                strategy);
    }
}
