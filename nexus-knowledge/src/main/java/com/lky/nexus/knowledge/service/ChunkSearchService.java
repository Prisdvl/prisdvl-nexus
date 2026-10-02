package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFailure;
import com.lky.nexus.knowledge.search.SearchMode;

import java.util.List;

/**
 * 切片检索服务：关键词（BM25）、向量、以及 RRF 融合三种模式。
 *
 * <p>三种模式都对外暴露是刻意的：融合效果好不好、权重要不要调，
 * 只有在能单独看每一路结果时才判断得了（否则"融合得不错"只是感觉）。
 */
public interface ChunkSearchService {

    /**
     * 检索。
     *
     * @param topK 返回条数；<b>null 表示"没指定"</b>，这时用知识库自身的
     *             {@code retrieval_top_k} 配置（仍没有则用全局默认 {@code nexus.retrieval.top-k}）。
     *             相似度阈值同理取知识库的 {@code similarity_threshold}——
     *             也就是说，"按库把检索参数调好"这件事不需要调用方配合。
     * @param mode bm25 / vector / hybrid
     */
    List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, Integer topK, SearchMode mode);

    /**
     * 检索（带元数据过滤）。
     *
     * @param filters 标签 / 目录 / 指定文档；{@link RetrievalFilters#none()} 表示不过滤。
     *                三个条件 AND，标签内部 OR，目录含子目录（详见 {@link RetrievalFilters}）。
     */
    List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, Integer topK, SearchMode mode,
                           RetrievalFilters filters);

    /**
     * 多知识库检索：逐库召回后跨库归并（Phase 5.5）。
     *
     * <p><b>为什么叫 {@code searchMany} 而不是重载 {@code search}</b>：重载会让
     * {@code Long} 与 {@code List<Long>} 之间的 Java 重载解析、
     * 以及测试里裸 {@code any()} 的 Mockito 匹配同时变得含糊——
     * 后者尤其糟：它会让一批与本次改动无关的桩在编译期报"歧义调用"，
     * 而报错位置离真正的原因很远。换个名字从根上避开。
     *
     * <p><b>{@code topK} 的语义与单库不同</b>，这是最容易搞错的一点：
     * <ul>
     *   <li>每个库按<b>它自己的</b> {@code retrieval_top_k} 召回（不受 {@code topK} 影响）；</li>
     *   <li>{@code topK} 只决定<b>归并之后</b>保留多少条，不随库数放大；</li>
     *   <li>{@code topK} 传 null 时用全局默认 {@code nexus.retrieval.top-k}。</li>
     * </ul>
     * 换句话说，多传几个库不会让上下文变长——只会在同样的条数预算里引入更多来源。
     * 这与 Agent 侧 {@code knowledge_search} 的口径一致。
     *
     * <p>{@code knowledgeBaseIds} 中任意一个不存在或不属于该用户，整批失败（3001）而不是部分成功；
     * 重复 id 会去重。详见 {@link KnowledgeBaseService#requireAllOwned}。
     */
    List<SearchHit> searchMany(Long userId, List<Long> knowledgeBaseIds, String query, Integer topK,
                               SearchMode mode);

    /** 多知识库检索（带元数据过滤）：过滤条件对每个库分别生效。 */
    List<SearchHit> searchMany(Long userId, List<Long> knowledgeBaseIds, String query, Integer topK,
                               SearchMode mode, RetrievalFilters filters);

    /**
     * 多知识库检索，<b>并如实带出"哪个库这次没搜到"</b>（Phase 5.5.1）。
     *
     * <p>与 {@link #searchMany} 只差一件事：多库并行召回后，单个库的失败会被<b>隔离</b>
     * （否则一个库挂了会让另外几个库的资料一起作废），而隔离必须留下痕迹——
     * {@code failures} 就是痕迹。没有它就分不清"这个库挂了"与"这个库里本来就没有"，
     * 用户会看到模型说"你的笔记里没有 X"，而 X 正躺在那个失败的库里。
     *
     * <p><b>{@link #searchMany} 是它的薄包装</b>（丢弃 failures）。保留两个入口是因为
     * 绝大多数调用方（检索接口、Agent 工具）不需要失败明细，让它们都要处理一个新字段，
     * 只会把"不关心"写成"忽略"——不如让关心的一方明确地要。
     *
     * <p><b>哪些失败不被隔离</b>（它们仍然整批失败，因此不会出现在 {@code failures} 里）：
     * <ul>
     *   <li>归属校验（{@code requireAllOwned}，3001）：把越权探测变成"部分成功"比报错更糟；</li>
     *   <li>嵌入模型不一致（4006）：它在扇出<b>之前</b>预检，一个库不合格就不该让别的库白跑一轮。</li>
     * </ul>
     * 归属校验之后、单个库自己抛的异常才会被隔离。全部库都失败时不返回本结果，
     * 而是抛原异常——"所有库都挂了"绝不能伪装成"检索到 0 条"。
     */
    MultiKbSearchResult searchManyDetailed(Long userId, List<Long> knowledgeBaseIds, String query, Integer topK,
                                           SearchMode mode, RetrievalFilters filters);

    /**
     * 多库检索结果：命中的片段 + 这次没能参与检索的知识库。
     *
     * @param hits     跨库归并、截断后的结果（与 {@link #searchMany} 同一条路径、同一份语义）
     * @param failures 被隔离掉的库；空表示每个库都正常返回了（返回 0 条也算正常）
     */
    record MultiKbSearchResult(List<SearchHit> hits, List<RetrievalFailure> failures) {

        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }

    /**
     * 命中项（已补齐展示与引用所需的信息）。
     *
     * @param score           相关性得分：单路时是该路原始分数，融合时是 RRF 分数
     * @param source          bm25 / vector / hybrid
     * @param matchedBy       命中来源明细（如 {@code ["BM25 #2", "向量 #1"]}），让"为什么排前面"可解释
     * @param knowledgeBaseId 命中所属的知识库。多库检索时用来标明"这一段出自哪个库"；
     *                        生产路径恒为非 null——只有兼容构造器（见下）会产生 null。
     */
    record SearchHit(
            Long chunkId,
            Long documentId,
            String documentTitle,
            String headingPath,
            String content,
            double score,
            String source,
            List<String> matchedBy,
            Long knowledgeBaseId) {

        /**
         * 兼容构造器（{@code knowledgeBaseId = null}）。
         *
         * <p><b>只给冻结基线 {@code ChunkSearchServiceLegacyReference} 与测试桩用</b>；
         * 生产代码一律走 9 参构造器。
         *
         * <p>为什么不直接改那个冻结文件、让它的构造点补上库 id：参照实现是
         * "改造前行为"的可执行定义（Phase 3.1），它存在的意义就是**不许跟着接口漂**。
         * 为了适配一个新字段去改它，等于把回归基准一起改掉，这层保护就静默失效了。
         * 参照实现本来就是单库的，"结果来自哪个库"这个维度对它无从谈起，
         * 因此 null 是如实的，而不是"暂时没填"。
         */
        public SearchHit(Long chunkId, Long documentId, String documentTitle, String headingPath,
                         String content, double score, String source, List<String> matchedBy) {
            this(chunkId, documentId, documentTitle, headingPath, content, score, source, matchedBy, null);
        }
    }
}
