package com.lky.nexus.ai.rag.retriever;

import java.util.List;

/**
 * 检索端口：把"怎么把问题变成资料"从业务层收走。
 *
 * <p>引入这个端口之前，RAG 服务直接依赖 {@code ChunkSearchService}、而后者内部同时握着
 * Elasticsearch 客户端、pgvector、RRF 融合与回表拼装——任何一处变化（换向量库、
 * 加过滤条件、加重排）都要动那个"什么都知道"的 Service。抽象之后调用关系变成：
 *
 * <pre>
 *   RagAnswerService / Agent 工具 / 检索接口
 *              ↓  （只认这个接口 + RetrievalQuery）
 *        RetrieverRegistry
 *              ↓
 *   VectorRetriever / KeywordRetriever / HybridRetriever / RerankedRetriever
 * </pre>
 *
 * <p><b>放在 nexus-ai 而不是 nexus-knowledge</b>：模块依赖方向是
 * {@code common ← ai ← knowledge ← app}，knowledge 依赖 ai，ai 不能依赖 knowledge。
 * 端口放 ai、实现放 knowledge，业务侧（app）按需组合——这也是
 * Tool / Workflow 抽象要走同一条路（见平台化设计文档 §五十）。
 *
 * <p>实现必须遵守的两条：
 * <ol>
 *   <li><b>不越权</b>：查询范围只由 {@link RetrievalQuery#knowledgeBaseIds()} 决定，
 *       返回的每一条都必须来自其中某个库（出口处可断言）；</li>
 *   <li><b>不抛实现异常</b>：底层基础设施（ES / pgvector / 嵌入模型）不可用时，
 *       要么降级、要么抛出 {@code BusinessException}，不要把
 *       {@code ElasticsearchException} 这类实现细节漏给业务层——
 *       业务层没法针对它做判断，只能变成 500。</li>
 * </ol>
 */
public interface Retriever {

    /** 实现名，用于日志与观测（如 {@code vector} / {@code keyword} / {@code hybrid} / {@code hybrid-rerank}）。 */
    String name();

    /**
     * 本实现能承接的策略。
     *
     * <p>一个策略只能有一个实现认领（{@link RetrieverRegistry} 在启动时会强制这一点），
     * 因此这里返回的集合是"我负责哪些策略"，而不是"我能用哪些底层能力"。
     */
    java.util.Set<RetrievalStrategy> strategies();

    /** 执行检索。空查询返回空列表；无权访问的知识库应抛出 3001 而不是返回空。 */
    List<RetrievedDocument> retrieve(RetrievalQuery query);
}
