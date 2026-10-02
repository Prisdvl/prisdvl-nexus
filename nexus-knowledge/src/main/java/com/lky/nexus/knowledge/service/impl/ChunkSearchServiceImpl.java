package com.lky.nexus.knowledge.service.impl;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.ai.rag.retriever.RetrieverRegistry;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.retriever.MultiKnowledgeRetriever;
import com.lky.nexus.knowledge.rag.retriever.RecallSupport;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFailure;
import com.lky.nexus.knowledge.rag.retriever.RetrievalQueryFactory;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索门面：把面向用户的 {@link SearchMode} 映射到内部检索策略，并转成对外的 {@code SearchHit}。
 *
 * <p><b>它现在只做四件事</b>：校验归属、把参数装成 {@link RetrievalQuery}、
 * 把 {@link RetrievedDocument} 转成接口约定的 {@code SearchHit}，
 * 以及（Phase 5.5）多库时逐库扇出后用 {@link RecallSupport#merge} 归并。
 * 真正的检索逻辑（向量/关键词/融合/重排）都在
 * {@code com.lky.nexus.knowledge.rag.retriever} 下的 {@code Retriever} 实现里，
 * 由 {@link RetrieverRegistry} 派发。
 *
 * <p><b>多库为什么在这里扇出，而不是让 Retriever 层一次吃下整个列表</b>：
 * {@code RetrievalQuery} 只有一个 {@code topK} 字段，而下推成
 * {@code kb_id IN (...)} 会让所有库共用同一个召回深度——
 * "按库配置 retrieval_top_k"立刻失效。逐库扇出是唯一能保住这个语义的做法。
 * 代价是 Retriever 层那段"列表长度 &gt; 1"的分支在本路径上仍然走不到，
 * 以及这一层与它各有一份 loop + merge——这处重复是有意接受的，取舍记在 decisions.md B.46。
 *
 * <p><b>"怎么并行、失败了怎么办"不在这里</b>（Phase 5.5.1）：那些规则
 * （单库不切线程、按输入顺序装配、全部失败抛原异常、超时不算空结果）全部归口在
 * {@link MultiKnowledgeRetriever}。这里只描述"一个库要做什么"，
 * 并行与隔离是另一件事——两者混在一起，以后调并行策略就会动到检索语义。
 *
 * <p><b>失败的库在这里被降级成"空列表占位"，而不是被剔除</b>：
 * {@link RecallSupport#merge} 有一条"只有一个库就原样返回"的快路径，
 * 剔除失败库会让它被意外命中，于是全局截断悄悄失效。理由写在 {@code searchManyDetailed} 里。
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
    private final KnowledgeEmbeddingResolver embeddingResolver;
    private final MultiKnowledgeRetriever multiRetriever;

    public ChunkSearchServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                  RetrieverRegistry retrieverRegistry,
                                  RetrievalQueryFactory queryFactory,
                                  KnowledgeEmbeddingResolver embeddingResolver,
                                  MultiKnowledgeRetriever multiRetriever) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.retrieverRegistry = retrieverRegistry;
        this.queryFactory = queryFactory;
        this.embeddingResolver = embeddingResolver;
        this.multiRetriever = multiRetriever;
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
        long start = System.currentTimeMillis();
        List<RetrievedDocument> documents =
                retrieveOne(knowledgeBase, mode, query, topK, userId, strategy, effectiveFilters);
        List<SearchHit> results = documents.stream().map(ChunkSearchServiceImpl::toSearchHit).toList();
        log.info("检索「{}」→ 返回 {} 条，耗时 {} ms", query, results.size(),
                System.currentTimeMillis() - start);
        return results;
    }

    @Override
    public List<SearchHit> searchMany(Long userId, List<Long> knowledgeBaseIds, String query, Integer topK,
                                      SearchMode mode) {
        return searchMany(userId, knowledgeBaseIds, query, topK, mode, RetrievalFilters.none());
    }

    @Override
    public List<SearchHit> searchMany(Long userId, List<Long> knowledgeBaseIds, String query, Integer topK,
                                      SearchMode mode, RetrievalFilters filters) {
        return searchManyDetailed(userId, knowledgeBaseIds, query, topK, mode, filters).hits();
    }

    @Override
    public MultiKbSearchResult searchManyDetailed(Long userId, List<Long> knowledgeBaseIds, String query,
                                                  Integer topK, SearchMode mode, RetrievalFilters filters) {
        // 与单库一致：先校验归属（这一步顺带完成去重与保序），再判空查询——
        // 空查询 + 越权知识库必须仍然报"知识库不存在"，而不是悄悄返回空结果
        List<KnowledgeBasePO> knowledgeBases = knowledgeBaseService.requireAllOwned(userId, knowledgeBaseIds);
        if (knowledgeBases.isEmpty() || query == null || query.isBlank()) {
            return new MultiKbSearchResult(List.of(), List.of());
        }

        RetrievalStrategy strategy = toStrategy(mode);
        RetrievalFilters effectiveFilters = filters == null ? RetrievalFilters.none() : filters;
        // 归并后的全局上限。注意这里**不是**每库的召回深度——见下面逐库构造处的说明
        int mergeLimit = queryFactory.resolveTopK(topK, null);
        // 预检在扇出**之前**：一个库的嵌入模型与全局配置不一致就不该让别的库白跑一轮检索。
        // 也正因为它在前面，这类失败是整批失败而不是"某个库失败"——不进 failures（见接口注释）
        precheckEmbeddingModels(knowledgeBases);

        // 逐库并行召回（Phase 5.5.1）。requestedTopK 一律传 null，让每个库用自己的
        // retrieval_top_k 召回。这一步极易写错：RetrievalQueryFactory.resolveTopK 的优先级是
        // "调用方 > 知识库配置"，所以把 topK 传进去等于让**每个库**都用全局值召回，
        // retrieval_top_k 整列失效——而"让这一列真正生效"正是 Phase 3.2 的全部工作。
        // 参考实现见 KnowledgeSearchTool。
        //
        // 日志仍由 retrieveOne 逐库打在召回**之前**，因此并行之后这些日志会交错出现——
        // 这正是"真的并行"最直观的证据，排查时不必去找时间戳。
        MultiKnowledgeRetriever.FanOut<List<RetrievedDocument>> fanOut = multiRetriever.retrieve(
                knowledgeBases,
                knowledgeBase -> retrieveOne(
                        knowledgeBase, mode, query, null, userId, strategy, effectiveFilters));

        // 失败的库填空列表，而不是从列表里剔除。这一行看着多余，其实是在保归并的语义：
        // RecallSupport.merge 有一条 size()==1 的快路径（原样返回、不做 limit 截断），
        // 剔除失败库会让"3 个库挂了 2 个"变成单元素快路径，于是全局截断也一起被关掉——
        // 一个库的失败会顺手改掉另一个库结果的处理方式。占位之后列表长度与库数一致，行为不变。
        List<List<RetrievedDocument>> perKnowledgeBase = fanOut.ordered().stream()
                .map(documents -> documents == null ? List.<RetrievedDocument>of() : documents)
                .toList();

        List<RetrievedDocument> merged = RecallSupport.merge(perKnowledgeBase, mergeLimit);
        List<SearchHit> results = merged.stream().map(ChunkSearchServiceImpl::toSearchHit).toList();
        if (fanOut.hasFailures()) {
            // 部分库失败要留下痕迹（隔离不静默）：这里只是"这次检索发生了什么"的现场记录，
            // 计数与 WARN 由扇出统一负责，失败的库名则要一路传到检索事件与提示词里
            log.warn("多库检索「{}」：{} 个库失败（{}），本次结果只来自其余 {} 个库",
                    query, fanOut.failures().size(),
                    fanOut.failures().stream().map(RetrievalFailure::displayName).toList(),
                    knowledgeBases.size() - fanOut.failures().size());
        }
        log.info("多库检索「{}」mode={} 共 {} 个库 → 归并后 {} 条（归并上限 {}；各库按其 retrieval_top_k 召回）",
                query, mode, knowledgeBases.size(), results.size(), mergeLimit);
        return new MultiKbSearchResult(results, fanOut.failures());
    }

    /**
     * 单库召回：构造查询对象 → 交给策略实现。
     *
     * <p>{@code requestedTopK} 的含义与 {@link RetrievalQuery} 一致：null = 没指定，
     * 用该知识库自己的配置。单库路径如实透传调用方的值；多库路径必须传 null。
     *
     * <p>两条路径共用这一处，是为了保证"参数怎么解析、日志怎么打"只有一份实现——
     * 多库若各写一份，迟早在其中一条上少打一行日志、或者参数解析走岔。
     */
    private List<RetrievedDocument> retrieveOne(KnowledgeBasePO knowledgeBase, SearchMode mode, String query,
                                                Integer requestedTopK, Long userId,
                                                RetrievalStrategy strategy, RetrievalFilters filters) {
        RetrievalQuery retrievalQuery = queryFactory.forKnowledgeBase(
                knowledgeBase, query, null, requestedTopK, null, userId, strategy, filters);
        // 把"实际生效的参数"打在召回**之前**：调参时最常见的问题是"我改了配置怎么没反应"，
        // 而只有解析后的值能回答它（请求传了 10 还是"没传"在日志里看不出区别）。
        // 放在前面还有一个原因：检索失败（ES/向量库不可用）时会直接抛异常，
        // 而那时的参数往往正是要排查的对象——日志留在失败之后，失败场景反而什么都没有。
        // 过滤条件同理：用户"筛了却还是全库结果"时，只有这行日志能说明条件到底有没有生效。
        log.info("检索「{}」mode={} strategy={} topK={} threshold={} filters={}（知识库 {} 的配置 {} / {}）",
                query, mode, strategy, retrievalQuery.limit(),
                retrievalQuery.effectiveSimilarityThreshold(), filters.describe(), knowledgeBase.getId(),
                knowledgeBase.getRetrievalTopK(), knowledgeBase.getSimilarityThreshold());
        return retrieverRegistry.get(strategy).retrieve(retrievalQuery);
    }

    /**
     * 嵌入模型预检：在召回<b>之前</b>逐个库确认它的冻结模型与当前全局配置一致。
     *
     * <p>不做也可以——向量那一路自己会调 {@code KnowledgeEmbeddingResolver.resolve}，
     * 不一致照样抛 4006。但它有两个问题：一是要等轮到那个库才失败，
     * 前面的库已经白跑了一轮嵌入与检索；二是错误信息只提"两个模型名"，
     * 多库场景下<b>无法看出是哪个库</b>。所以在这里提前失败，并把库的名字与 id 补进信息。
     *
     * <p>顺带澄清一件容易搞反的事：这个检查比较的是"每个库 vs 全局配置"，
     * 不是"库与库互相比较"。正因为每个库都必须等于同一个全局配置，
     * 通过检查的库之间才必然彼此一致——向量分数跨库可比就是这么来的（见 {@link RecallSupport#merge}）。
     */
    private void precheckEmbeddingModels(List<KnowledgeBasePO> knowledgeBases) {
        for (KnowledgeBasePO knowledgeBase : knowledgeBases) {
            try {
                embeddingResolver.resolve(knowledgeBase);
            } catch (BusinessException ex) {
                throw new BusinessException(ex.getCode(),
                        "知识库「%s」(id=%d) 无法参与本次检索：%s"
                                .formatted(knowledgeBase.getName(), knowledgeBase.getId(), ex.getMessage()));
            }
        }
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
     * <p>{@code knowledgeBaseId} 从 Phase 5.5 起带出来：多库检索时它回答"这一段出自哪个库"，
     * 是引用能标明来源的前提。剩下没带出来的 {@code fileName / page} 仍是暂时的
     * （引用要定位到页码时再补），取舍理由同上：只带当前确实要用的。
     *
     * <p>注意这里传入的 {@code knowledgeBaseId} 来自 {@link RetrievedDocument} 而不是入参——
     * 检索器是逐库召回的，文档自己知道它出自哪个库，比外部再传一遍更可靠
     * （外部传错了也不会有人发现）。
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
                document.matchedBy(),
                document.knowledgeBaseId());
    }
}
