package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.dto.DegradedKb;
import com.lky.nexus.knowledge.model.dto.ExplainFusedHit;
import com.lky.nexus.knowledge.model.dto.ExplainPathHit;
import com.lky.nexus.knowledge.model.dto.ExplainRerankHit;
import com.lky.nexus.knowledge.model.dto.SearchExplainResponse;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.Reranker;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 检索过程剖析（{@code POST /api/search/explain}）。
 *
 * <p><b>它和 {@code /api/search} 的关系</b>：同一个库、同一套参数，区别只在"看到哪一层"。
 * 搜索给结论，explain 给过程——两路原始名次、RRF 融合分、重排分。
 * 所以这里刻意<b>不压缩</b>（两路照实返回）、<b>不落库</b>（只读）、<b>不换算法</b>
 * （融合用的就是线上那个 {@link RrfFusion}，k 也是同一个 {@code DEFAULT_K}）。
 *
 * <p><b>名次的语义要说清楚</b>：{@code bm25Rank} / {@code vectorRank} / {@code rank}
 * 都是<b>库内</b>名次。召回是逐库进行的，RRF 分 = {@code 1/(60+bm25Rank) + 1/(60+vectorRank)}，
 * 跨库比较融合分是成立的（分只与名次有关），但"某库第 3 名"和"另一库第 3 名"同名次不同质，
 * 所以不去编一个全局的 BM25 名次。
 *
 * <p><b>为什么回表失败不丢条目</b>：调参时最常遇到的正是"索引里有、库里没有"（孤儿切片）。
 * 如果这时把条目删掉，{@code rank} 与 {@code fusedScore} 就会凭空错位，人也就没法手算了。
 * 因此标题/正文拿不到时留 {@code null}，条目保留。
 */
@Service
public class SearchExplainService {

    private static final Logger log = LoggerFactory.getLogger(SearchExplainService.class);

    /** 与 {@code RerankedRetriever} 同一口径：只重排前 8 条（再多只是多花钱）。 */
    private static final int RERANK_CANDIDATES = 8;

    /** 片段截断长度：explain 是给人看的，正文太长会把"名次与分数"淹掉。 */
    private static final int SNIPPET_MAX_CHARS = 240;

    private final RetrievalQueryFactory queryFactory;
    private final RetrievalFilterResolver filterResolver;
    private final ExplainRecaller recaller;
    private final ChunkHydrator hydrator;
    private final MultiKnowledgeRetriever multiRetriever;
    private final Reranker reranker;

    public SearchExplainService(RetrievalQueryFactory queryFactory,
                                RetrievalFilterResolver filterResolver,
                                ExplainRecaller recaller,
                                ChunkHydrator hydrator,
                                MultiKnowledgeRetriever multiRetriever,
                                Reranker reranker) {
        this.queryFactory = queryFactory;
        this.filterResolver = filterResolver;
        this.recaller = recaller;
        this.hydrator = hydrator;
        this.multiRetriever = multiRetriever;
        this.reranker = reranker;
    }

    /**
     * 剖析一次 HYBRID 检索。
     *
     * @param userId         归属用户（调用方已完成库范围解析）
     * @param knowledgeBases 本次参与剖析的库（已按用户过滤）
     * @param query          检索词
     * @param topK           每库的候选深度依据；缺省用库自己的 retrieval_top_k
     * @param rerank         是否附带重排结果
     * @param filters        元数据过滤；{@code null} 视为不过滤
     */
    public SearchExplainResponse explain(Long userId, List<KnowledgeBasePO> knowledgeBases, String query,
                                         Integer topK, boolean rerank, RetrievalFilters filters) {
        long startedAt = System.currentTimeMillis();
        if (knowledgeBases == null || knowledgeBases.isEmpty()) {
            return new SearchExplainResponse(List.of(), List.of(), List.of(), List.of(), 0L, List.of());
        }

        MultiKnowledgeRetriever.FanOut<PerKnowledgeBaseExplain> fanOut;
        try {
            fanOut = multiRetriever.retrieve(knowledgeBases,
                    knowledgeBase -> explainOne(knowledgeBase, query, topK, userId, filters));
        } catch (BusinessException ex) {
            // 单库不走扇出（不隔离异常）。explain 是调参工具，这时候给"全部降级 + 原因"
            // 比抛一个 503 更有用：界面至少能显示"为什么一条都没有"。
            log.warn("检索剖析失败 userId={} kbs={}", userId, knowledgeBases.size(), ex);
            List<DegradedKb> degraded = knowledgeBases.stream()
                    .map(kb -> new DegradedKb(kb.getId(), kb.getName(), ex.getMessage()))
                    .toList();
            return new SearchExplainResponse(List.of(), List.of(), List.of(), List.of(),
                    System.currentTimeMillis() - startedAt, degraded);
        }

        List<ExplainPathHit> vectorPath = new ArrayList<>();
        List<ExplainPathHit> keywordPath = new ArrayList<>();
        List<FusedCandidate> fused = new ArrayList<>();
        for (PerKnowledgeBaseExplain part : fanOut.ordered()) {
            if (part == null) {
                // 失败位的占位 null：对应的库已经进 failures，这里跳过即可
                continue;
            }
            vectorPath.addAll(part.vector());
            keywordPath.addAll(part.bm25());
            fused.addAll(part.fused());
        }
        // 融合分只与库内名次有关，跨库可以直接排；同分时保持"先出现的库在前"
        fused.sort(Comparator.comparingDouble(FusedCandidate::score).reversed());

        List<ExplainFusedHit> fusedHits = new ArrayList<>(fused.size());
        for (int i = 0; i < fused.size(); i++) {
            FusedCandidate candidate = fused.get(i);
            RetrievedDocument document = candidate.document();
            fusedHits.add(new ExplainFusedHit(
                    candidate.kbId(),
                    candidate.chunkId(),
                    document == null ? null : document.documentId(),
                    document == null ? null : document.documentTitle(),
                    document == null ? null : document.headingPath(),
                    snippet(document == null ? null : document.content()),
                    i + 1,
                    candidate.score(),
                    candidate.bm25Rank(),
                    candidate.vectorRank(),
                    candidate.matchedBy()));
        }

        List<ExplainRerankHit> reranked = rerank ? rerank(query, fused) : List.of();
        List<DegradedKb> degraded = fanOut.failures().stream()
                .map(failure -> new DegradedKb(failure.knowledgeBaseId(), failure.knowledgeBaseName(), failure.reason()))
                .toList();

        return new SearchExplainResponse(vectorPath, keywordPath, fusedHits, reranked,
                System.currentTimeMillis() - startedAt, degraded);
    }

    /** 单库：两路召回 → 回表（尽力）→ RRF。任何一步都不裁剪结果。 */
    private PerKnowledgeBaseExplain explainOne(KnowledgeBasePO knowledgeBase, String query, Integer topK,
                                               Long userId, RetrievalFilters filters) {
        RetrievalQuery retrieval = queryFactory.forKnowledgeBase(knowledgeBase, query, null, topK, null,
                userId, RetrievalStrategy.HYBRID, filters);
        int depth = retrieval.candidateDepth();

        RetrievalFilterResolver.FilterPlan plan = filterResolver.resolve(knowledgeBase.getId(), filters);
        if (plan.matchNothing()) {
            // 过滤条件在这条库上必然无解：如实返回空，而不是"当作没过滤"
            return new PerKnowledgeBaseExplain(knowledgeBase.getId(), List.of(), List.of(), List.of());
        }

        List<ChunkSearchHit> keywordHits = recaller.keywordHits(knowledgeBase.getId(), query, depth, filters);
        List<ChunkSearchHit> vectorHits = recaller.vectorHits(knowledgeBase, query, depth,
                retrieval.effectiveSimilarityThreshold(), plan.documentIds());
        Map<Long, RetrievedDocument> documents = hydrate(knowledgeBase.getId(), keywordHits, vectorHits);

        // 深度取"两路候选总数"：explain 不做截断，否则看不出"第 21 条为什么没进结果"
        int untrimmed = Math.max(keywordHits.size() + vectorHits.size(), 1);
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(
                List.of(new RrfFusion.RankedList("bm25", chunkIdsOf(keywordHits)),
                        new RrfFusion.RankedList("vector", chunkIdsOf(vectorHits))),
                untrimmed, RrfFusion.DEFAULT_K);

        List<FusedCandidate> candidates = new ArrayList<>(fused.size());
        for (RrfFusion.FusedHit hit : fused) {
            candidates.add(new FusedCandidate(knowledgeBase.getId(), hit.chunkId(), hit.score(),
                    hit.bm25Rank(), hit.vectorRank(), hit.matchedBy(), documents.get(hit.chunkId())));
        }
        return new PerKnowledgeBaseExplain(knowledgeBase.getId(),
                pathHits(knowledgeBase.getId(), vectorHits, documents),
                pathHits(knowledgeBase.getId(), keywordHits, documents),
                candidates);
    }

    /** 两路命中的 chunkId 按出现顺序去重，交给 RRF 计算名次。 */
    private static List<Long> chunkIdsOf(List<ChunkSearchHit> hits) {
        List<Long> ids = new ArrayList<>(hits.size());
        for (ChunkSearchHit hit : hits) {
            if (hit != null && hit.chunkId() != null) {
                ids.add(hit.chunkId());
            }
        }
        return ids;
    }

    private static List<ExplainPathHit> pathHits(Long knowledgeBaseId, List<ChunkSearchHit> hits,
                                                 Map<Long, RetrievedDocument> documents) {
        List<ExplainPathHit> path = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            ChunkSearchHit hit = hits.get(i);
            RetrievedDocument document = documents.get(hit.chunkId());
            path.add(new ExplainPathHit(
                    knowledgeBaseId,
                    hit.chunkId(),
                    document == null ? null : document.documentId(),
                    document == null ? null : document.documentTitle(),
                    document == null ? null : document.headingPath(),
                    snippet(document == null ? null : document.content()),
                    hit.score(),
                    i + 1));
        }
        return path;
    }

    /**
     * 回表补齐标题与正文（尽力而为）。
     *
     * <p>复用 {@link ChunkHydrator} 而不是自己查：孤儿切片的告警、原文优先的取值规则
     * 都该只有一处实现。这里唯一的不同是<b>结果按 chunkId 建索引</b>而不是按顺序消费，
     * 因为 explain 需要在"两路原始顺序"和"融合顺序"两个视角里反复取同一条。
     */
    private Map<Long, RetrievedDocument> hydrate(Long knowledgeBaseId, List<ChunkSearchHit> keywordHits,
                                                 List<ChunkSearchHit> vectorHits) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(chunkIdsOf(keywordHits));
        ids.addAll(chunkIdsOf(vectorHits));
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ChunkHydrator.RecalledChunk> recalled = ids.stream()
                .map(id -> new ChunkHydrator.RecalledChunk(id, 0d, "explain", List.of()))
                .toList();
        Map<Long, RetrievedDocument> documents = new HashMap<>();
        for (RetrievedDocument document : hydrator.hydrate(knowledgeBaseId, recalled)) {
            documents.put(document.chunkId(), document);
        }
        return documents;
    }

    /**
     * 重排前 {@value #RERANK_CANDIDATES} 条；失败就返回空列表（由前端提示"未重排"），
     * 绝不因为重排挂了而丢掉融合结果。
     */
    private List<ExplainRerankHit> rerank(String query, List<FusedCandidate> fused) {
        if (fused.size() < 2) {
            return List.of();
        }
        int headSize = Math.min(fused.size(), RERANK_CANDIDATES);
        List<FusedCandidate> head = fused.subList(0, headSize);
        List<Reranker.Candidate> candidates = new ArrayList<>(headSize);
        for (FusedCandidate candidate : head) {
            RetrievedDocument document = candidate.document();
            candidates.add(new Reranker.Candidate(
                    candidate.chunkId(),
                    document == null ? null : document.headingPath(),
                    document == null ? null : document.content()));
        }

        List<Double> scores;
        try {
            scores = reranker.score(query, candidates);
        } catch (RuntimeException ex) {
            log.warn("检索剖析的重排失败，保留融合顺序 query={}", query, ex);
            return List.of();
        }
        if (scores == null || scores.size() != headSize) {
            log.warn("检索剖析的重排分数条数不符 expected={} actual={}", headSize, scores == null ? -1 : scores.size());
            return List.of();
        }

        List<Integer> order = new ArrayList<>(headSize);
        for (int i = 0; i < headSize; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer index) -> scores.get(index)).reversed());

        List<ExplainRerankHit> reranked = new ArrayList<>(headSize);
        for (int i = 0; i < order.size(); i++) {
            FusedCandidate candidate = head.get(order.get(i));
            reranked.add(new ExplainRerankHit(candidate.kbId(), candidate.chunkId(), i + 1, scores.get(order.get(i))));
        }
        return reranked;
    }

    private static String snippet(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.length() <= SNIPPET_MAX_CHARS ? trimmed : trimmed.substring(0, SNIPPET_MAX_CHARS) + "…";
    }

    /** 一个库的剖析结果（向量路 / 关键词路 / 融合）。 */
    private record PerKnowledgeBaseExplain(
            Long kbId,
            List<ExplainPathHit> vector,
            List<ExplainPathHit> bm25,
            List<FusedCandidate> fused) {
    }

    /**
     * 融合后的一条，带上回表结果（可能为 null，见类注释）。
     *
     * <p>{@code kbId} 单独存一份而不是从 {@code document} 里取：
     * 回表落空时 document 是 null，而"这条来自哪个库"是剖析里最不能丢的信息。
     */
    private record FusedCandidate(
            Long kbId,
            Long chunkId,
            double score,
            Integer bm25Rank,
            Integer vectorRank,
            List<String> matchedBy,
            RetrievedDocument document) {
    }
}
