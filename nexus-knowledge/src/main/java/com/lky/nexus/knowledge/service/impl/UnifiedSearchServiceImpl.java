package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.dto.DegradedKb;
import com.lky.nexus.knowledge.model.dto.SearchExplainResponse;
import com.lky.nexus.knowledge.model.dto.SearchRequest;
import com.lky.nexus.knowledge.model.dto.UnifiedSearchHit;
import com.lky.nexus.knowledge.model.dto.UnifiedSearchResponse;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.Reranker;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFailure;
import com.lky.nexus.knowledge.rag.retriever.SearchExplainService;
import com.lky.nexus.knowledge.search.EmptyReason;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.service.UnifiedSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨库统一检索的编排层。
 *
 * <p><b>为什么这一层不写召回</b>：单库召回（{@code ChunkSearchService}）、
 * 多库扇出与隔离（{@code MultiKnowledgeRetriever}）、归并（{@code RecallSupport}）
 * 都已经存在并有各自的测试。这里如果再写一遍"按库并行 + 合并 + 排序"，
 * 就会出现第二套行为：一处改了截断规则、另一处没改。所以整条链路只有两处新逻辑——
 * <b>范围解析</b>（空 kbIds / 禁用库）与<b>失败翻译</b>（degradedKbs）。
 *
 * <p><b>{@code topK} 的语义沿用既有约定</b>：它只决定"归并后留下多少条"，
 * 每库真正召回多少由该库自己的 {@code retrieval_top_k} 决定（用户调参应该影响所有库，
 * 而不是被一次请求里的全局数字盖掉）。
 */
@Service
public class UnifiedSearchServiceImpl implements UnifiedSearchService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedSearchServiceImpl.class);

    /** 与 {@code RerankedRetriever} 同一口径：只重排前 8 条。 */
    private static final int RERANK_CANDIDATES = 8;

    /** 响应里的正文片段上限：跨库检索一次可能几十条，全文会让响应体失控。 */
    private static final int SNIPPET_MAX_CHARS = 400;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ChunkSearchService chunkSearchService;
    private final ChunkIndexService chunkIndexService;
    private final SearchExplainService searchExplainService;
    private final Reranker reranker;

    public UnifiedSearchServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                    KnowledgeBaseMapper knowledgeBaseMapper,
                                    ChunkSearchService chunkSearchService,
                                    ChunkIndexService chunkIndexService,
                                    SearchExplainService searchExplainService,
                                    Reranker reranker) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.chunkSearchService = chunkSearchService;
        this.chunkIndexService = chunkIndexService;
        this.searchExplainService = searchExplainService;
        this.reranker = reranker;
    }

    @Override
    public UnifiedSearchResponse search(Long userId, SearchRequest request) {
        long startedAt = System.currentTimeMillis();
        Scope scope = resolveScope(userId, request.kbIds());
        List<DegradedKb> degraded = new ArrayList<>(scope.skipped());

        if (scope.searchable().isEmpty()) {
            // 只有"本来就没有库可搜"才叫 NO_KB；显式点名的库全被禁用时，事实是"搜了但没有结果"
            String reason = scope.skipped().isEmpty() ? EmptyReason.NO_KB.name() : EmptyReason.NO_RESULTS.name();
            return new UnifiedSearchResponse(List.of(), reason, System.currentTimeMillis() - startedAt, degraded);
        }

        List<Long> ids = scope.searchable().stream().map(KnowledgeBasePO::getId).toList();
        Map<Long, String> names = new HashMap<>();
        for (KnowledgeBasePO knowledgeBase : scope.searchable()) {
            names.put(knowledgeBase.getId(), knowledgeBase.getName());
        }

        ChunkSearchService.MultiKbSearchResult result = chunkSearchService.searchManyDetailed(
                userId, ids, request.q().strip(), request.topK(), SearchMode.parse(request.mode()),
                toFilters(request.filters()));
        for (RetrievalFailure failure : result.failures()) {
            degraded.add(new DegradedKb(failure.knowledgeBaseId(), failure.knowledgeBaseName(), failure.reason()));
        }

        List<ChunkSearchService.SearchHit> ordered = result.hits();
        if (Boolean.TRUE.equals(request.rerank())) {
            ordered = rerankHits(request.q().strip(), ordered);
        }

        List<UnifiedSearchHit> hits = new ArrayList<>(ordered.size());
        for (ChunkSearchService.SearchHit hit : ordered) {
            hits.add(new UnifiedSearchHit(
                    hit.knowledgeBaseId(),
                    names.get(hit.knowledgeBaseId()),
                    hit.chunkId(),
                    hit.documentId(),
                    hit.documentTitle(),
                    hit.headingPath(),
                    snippet(hit.content()),
                    hit.score(),
                    hit.source(),
                    hit.matchedBy()));
        }

        String emptyReason = hits.isEmpty() ? emptyReasonOf(userId, scope.searchable()) : null;
        return new UnifiedSearchResponse(hits, emptyReason, System.currentTimeMillis() - startedAt, degraded);
    }

    @Override
    public SearchExplainResponse explain(Long userId, SearchRequest request) {
        long startedAt = System.currentTimeMillis();
        Scope scope = resolveScope(userId, request.kbIds());
        if (scope.searchable().isEmpty()) {
            return new SearchExplainResponse(List.of(), List.of(), List.of(), List.of(),
                    System.currentTimeMillis() - startedAt, scope.skipped());
        }
        SearchExplainResponse response = searchExplainService.explain(
                userId, scope.searchable(), request.q().strip(), request.topK(),
                Boolean.TRUE.equals(request.rerank()), toFilters(request.filters()));
        if (scope.skipped().isEmpty()) {
            return response;
        }
        // 被禁用/越权跳过的库与剖析自身的失败放在一起：界面上只有一个"这些库没参与"
        List<DegradedKb> degraded = new ArrayList<>(scope.skipped());
        degraded.addAll(response.degradedKbs());
        return new SearchExplainResponse(response.vector(), response.bm25(), response.fused(),
                response.rerank(), response.tookMs(), degraded);
    }

    /**
     * 解析本次检索的库范围。
     *
     * <p>{@code kbIds} 为空时按"当前用户已启用的全部库"取：这是界面上"全部知识库"的默认语义，
     * 顺带天然避免了越权。显式传 id 时逐个校验归属（{@code requireAllOwned} 越权直接 3001），
     * 其中被禁用的库不再参与检索，但会作为 {@code degradedKbs} 如实回报——
     * 用户明明点了它，就得被告知"它在，但它被禁用了"。
     */
    private Scope resolveScope(Long userId, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            List<KnowledgeBasePO> enabled = knowledgeBaseMapper.selectList(
                    Wrappers.<KnowledgeBasePO>lambdaQuery()
                            .eq(KnowledgeBasePO::getUserId, userId)
                            .eq(KnowledgeBasePO::getStatus, "ENABLED")
                            .orderByAsc(KnowledgeBasePO::getCreatedAt));
            return new Scope(enabled, List.of());
        }
        List<KnowledgeBasePO> owned = knowledgeBaseService.requireAllOwned(userId, kbIds);
        List<KnowledgeBasePO> searchable = new ArrayList<>(owned.size());
        List<DegradedKb> skipped = new ArrayList<>();
        for (KnowledgeBasePO knowledgeBase : owned) {
            if ("DISABLED".equalsIgnoreCase(knowledgeBase.getStatus())) {
                skipped.add(new DegradedKb(knowledgeBase.getId(), knowledgeBase.getName(), "知识库已禁用，未参与检索"));
            } else {
                searchable.add(knowledgeBase);
            }
        }
        return new Scope(searchable, skipped);
    }

    /**
     * 对归并后的前几条做重排，规则与 {@code RerankedRetriever} 保持一致：
     * 少于 2 条没必要、只重排前 8 条、分数条数对不上就放弃重排（保持融合顺序）。
     *
     * <p>为什么在归并之后重排而不是逐库重排：重排模型只认"问题 + 候选"，候选池跨库越全越准；
     * 逐库重排会把各库自己的第 1 名都抬到前面，等于按库洗牌。
     */
    private List<ChunkSearchService.SearchHit> rerankHits(String query, List<ChunkSearchService.SearchHit> hits) {
        if (hits.size() < 2) {
            return hits;
        }
        int headSize = Math.min(hits.size(), RERANK_CANDIDATES);
        List<ChunkSearchService.SearchHit> head = new ArrayList<>(hits.subList(0, headSize));
        List<Reranker.Candidate> candidates = new ArrayList<>(headSize);
        for (ChunkSearchService.SearchHit hit : head) {
            candidates.add(new Reranker.Candidate(hit.chunkId(), hit.headingPath(), hit.content()));
        }
        List<Double> scores;
        try {
            scores = reranker.score(query, candidates);
        } catch (RuntimeException ex) {
            log.warn("跨库检索的重排失败，保留融合顺序 query={}", query, ex);
            return hits;
        }
        if (scores == null || scores.size() != headSize) {
            log.warn("跨库检索的重排分数条数不符 expected={} actual={}", headSize, scores == null ? -1 : scores.size());
            return hits;
        }

        List<Integer> order = new ArrayList<>(headSize);
        for (int i = 0; i < headSize; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer index) -> scores.get(index)).reversed());

        List<ChunkSearchService.SearchHit> reranked = new ArrayList<>(hits.size());
        for (int i = 0; i < order.size(); i++) {
            ChunkSearchService.SearchHit origin = head.get(order.get(i));
            List<String> matchedBy = new ArrayList<>(origin.matchedBy() == null ? List.of() : origin.matchedBy());
            matchedBy.add("重排 #" + (i + 1));
            reranked.add(new ChunkSearchService.SearchHit(origin.chunkId(), origin.documentId(),
                    origin.documentTitle(), origin.headingPath(), origin.content(),
                    scores.get(order.get(i)), "rerank", List.copyOf(matchedBy), origin.knowledgeBaseId()));
        }
        // 没进重排窗口的尾部原样接上：截断它们会让 topK 变小，等于偷偷改了契约
        reranked.addAll(hits.subList(headSize, hits.size()));
        return reranked;
    }

    /**
     * 零命中时区分三态：没有库 / 没有索引 / 真的没有。
     *
     * <p>与单库端点同一算法（{@code EmptyReason.ofEmpty}），只是把各库的覆盖率求和：
     * "所有库加起来都还没建索引"才叫 NO_INDEX；"有索引但这次没命中"是 NO_RESULTS。
     */
    private String emptyReasonOf(Long userId, List<KnowledgeBasePO> knowledgeBases) {
        long chunkCount = 0;
        long indexedChunkCount = 0;
        for (KnowledgeBasePO knowledgeBase : knowledgeBases) {
            try {
                ChunkIndexService.IndexCoverage coverage = chunkIndexService.coverage(userId, knowledgeBase.getId());
                chunkCount += coverage.chunkCount();
                indexedChunkCount += coverage.indexedChunkCount();
            } catch (RuntimeException ex) {
                // 覆盖率只是解释性信息，拿不到不能影响"零命中"这个事实
                log.debug("统计索引覆盖率失败 knowledgeBaseId={}", knowledgeBase.getId(), ex);
            }
        }
        return EmptyReason.ofEmpty(0, chunkCount, indexedChunkCount).name();
    }

    private static RetrievalFilters toFilters(SearchRequest.SearchFilters filters) {
        if (filters == null) {
            return RetrievalFilters.none();
        }
        List<Long> documentIds = filters.documentId() == null ? List.of() : List.of(filters.documentId());
        return new RetrievalFilters(filters.tags(), filters.folder(), documentIds);
    }

    private static String snippet(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.length() <= SNIPPET_MAX_CHARS ? trimmed : trimmed.substring(0, SNIPPET_MAX_CHARS) + "…";
    }

    /** 解析后的库范围：能搜的 + 被跳过的（禁用）。 */
    private record Scope(List<KnowledgeBasePO> searchable, List<DegradedKb> skipped) {
    }
}
