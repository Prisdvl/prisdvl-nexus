package com.lky.nexus.knowledge.service.impl;

import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.RrfFusion;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import com.lky.nexus.knowledge.vector.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【冻结的参照实现 · 只存在于测试代码里，禁止修改】
 *
 * <p>这是 Retriever 端口重构（Phase 3.1）之前 {@code ChunkSearchServiceImpl} 的逐字副本。
 * 唯一的改动：类名、去掉 {@code @Service}、去掉日志调用（日志不是可观测行为的一部分），
 * 以及<b>不再 {@code implements ChunkSearchService}</b>——检索逻辑一字未动，
 * 只是不再绑定接口签名。
 *
 * <p>为什么要脱离接口（Phase 3.2 起）：接口的 {@code topK} 由 {@code int} 放宽为
 * {@code Integer}（null = 用知识库配置），而参照实现永远不该跟着接口漂——
 * 它定义的是"改造前的行为"，不是"当年那份接口"。脱离之后，
 * 以后接口再怎么改，这个文件都无需改动，也就不会有人为了让它编译而顺手改它的行为。
 *
 * <p>它的存在只有一个目的：给 {@code ChunkSearchServiceParityTest} 提供一个
 * <b>可执行的"改造前行为"定义</b>。重构最怕"看起来没问题"——断言几个字段通过，
 * 不代表行为没变：结果顺序、重复命中怎么算名次、孤儿命中怎么处理、
 * 分数取自哪一路，这些都会在重构中静默变化，而且没有任何报错。
 * 有了它，"行为不变"就从一句说法变成每次构建都跑一遍的机器证明。
 *
 * <p><b>绝对不要"顺手修"这里的任何东西</b>——包括看起来像 bug 的地方
 * （比如重复命中仍然消耗一个名次、孤儿命中被跳过导致返回条数少于 limit）。
 * 它的价值恰恰在于<b>如实保留旧行为</b>；一旦被"修正"，它就不再能证明
 * 新实现与旧实现一致，这层保护会静默失效。要改行为，改生产代码，
 * 然后有意识地更新 {@code ChunkSearchServiceParityTest} 里的期望值。
 */
public class ChunkSearchServiceLegacyReference {

    private static final int MAX_TOP_K = 100;
    private static final int MAX_CANDIDATES = 200;
    private static final double MIN_VECTOR_SCORE = 0.25;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeEmbeddingResolver embeddingResolver;
    private final ChunkIndex chunkIndex;
    private final VectorStore vectorStore;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    public ChunkSearchServiceLegacyReference(KnowledgeBaseService knowledgeBaseService,
                                             KnowledgeEmbeddingResolver embeddingResolver,
                                             ChunkIndex chunkIndex,
                                             VectorStore vectorStore,
                                             ChunkMapper chunkMapper,
                                             DocumentMapper documentMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingResolver = embeddingResolver;
        this.chunkIndex = chunkIndex;
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    public List<ChunkSearchService.SearchHit> search(Long userId, Long knowledgeBaseId, String query, int topK, SearchMode mode) {
        KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = Math.clamp(topK, 1, MAX_TOP_K);
        int depth = Math.min(MAX_CANDIDATES, limit * 3);

        List<ChunkSearchHit> bm25Hits = mode.usesBm25()
                ? chunkIndex.search(knowledgeBaseId, query, depth) : List.of();
        List<ChunkSearchHit> vectorHits = mode.usesVector()
                ? vectorSearch(knowledgeBase, query, depth) : List.of();

        return switch (mode) {
            case BM25 -> hydrate(ranked(bm25Hits, "bm25", limit));
            case VECTOR -> hydrate(ranked(vectorHits, "vector", limit));
            case HYBRID -> hydrate(fuse(bm25Hits, vectorHits, limit));
        };
    }

    // ------------------------------------------------------------------

    /** 单路：按原始分数排序（两路都已经是有序的），带上来源与排名说明。 */
    private LinkedHashMap<Long, HitMeta> ranked(List<ChunkSearchHit> hits, String source, int limit) {
        LinkedHashMap<Long, HitMeta> result = new LinkedHashMap<>();
        int rank = 1;
        for (ChunkSearchHit hit : hits) {
            if (result.size() >= limit) {
                break;
            }
            String label = ("vector".equals(source) ? "向量 #" : "BM25 #") + rank;
            result.putIfAbsent(hit.chunkId(), new HitMeta(hit.score(), source, List.of(label)));
            rank++;
        }
        return result;
    }

    /** 融合：RRF 只看排名（两路分数不可比），并保留每路排名用于解释。 */
    private LinkedHashMap<Long, HitMeta> fuse(List<ChunkSearchHit> bm25Hits,
                                              List<ChunkSearchHit> vectorHits, int limit) {
        List<RrfFusion.RankedList> lists = List.of(
                new RrfFusion.RankedList("bm25", bm25Hits.stream().map(ChunkSearchHit::chunkId).toList()),
                new RrfFusion.RankedList("vector", vectorHits.stream().map(ChunkSearchHit::chunkId).toList()));

        LinkedHashMap<Long, HitMeta> result = new LinkedHashMap<>();
        for (RrfFusion.FusedHit fused : RrfFusion.fuse(lists, limit)) {
            result.put(fused.chunkId(), new HitMeta(fused.score(), "hybrid", fused.matchedBy()));
        }
        return result;
    }

    private List<ChunkSearchHit> vectorSearch(KnowledgeBasePO knowledgeBase, String query, int depth) {
        EmbeddingClient client = embeddingResolver.resolve(knowledgeBase);
        float[] queryVector = client.embed(query);
        return vectorStore.search(knowledgeBase.getId(), queryVector, depth, MIN_VECTOR_SCORE).stream()
                .map(hit -> new ChunkSearchHit(hit.chunkId(), hit.score(), "vector"))
                .toList();
    }

    /** 按相关性顺序回表补齐正文与文档标题。 */
    private List<ChunkSearchService.SearchHit> hydrate(LinkedHashMap<Long, HitMeta> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, ChunkPO> chunks = new HashMap<>();
        for (ChunkPO chunk : chunkMapper.selectByIds(List.copyOf(hits.keySet()))) {
            chunks.put(chunk.getId(), chunk);
        }
        Map<Long, DocumentPO> documents = new HashMap<>();
        List<Long> documentIds = chunks.values().stream().map(ChunkPO::getDocumentId).distinct().toList();
        if (!documentIds.isEmpty()) {
            for (DocumentPO document : documentMapper.selectByIds(documentIds)) {
                documents.put(document.getId(), document);
            }
        }

        List<ChunkSearchService.SearchHit> results = new ArrayList<>(hits.size());
        hits.forEach((chunkId, meta) -> {
            ChunkPO chunk = chunks.get(chunkId);
            if (chunk == null) {
                // 旧实现这里是 log.warn：索引与数据库不一致。日志不是可观测行为，故此副本略去。
                return;
            }
            DocumentPO document = documents.get(chunk.getDocumentId());
            results.add(new ChunkSearchService.SearchHit(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    document == null ? null : document.getTitle(),
                    chunk.getHeadingPath(),
                    chunk.getRawContent() == null ? chunk.getContent() : chunk.getRawContent(),
                    meta.score(),
                    meta.source(),
                    meta.matchedBy()));
        });
        return results;
    }

    /** 命中项的元信息（分数、来源、命中说明）。 */
    private record HitMeta(double score, String source, List<String> matchedBy) {
    }
}
