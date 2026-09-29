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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索实现：两路召回 + RRF 融合。
 *
 * <p>流程：
 * <pre>
 *   query ─┬─ BM25(ES/IK) ────────┐
 *          └─ 嵌入 → pgvector ────┴─ RRF 融合 ─ 回表补齐正文/标题 ─ 结果
 * </pre>
 *
 * <p>三个实现要点：
 * <ol>
 *   <li><b>召回深度大于返回条数</b>：融合前每路多取候选（默认 3 倍），
 *       否则"两路各自的第一名"可能因为一条不在另一路的候选里而被丢掉；</li>
 *   <li><b>回表顺序必须按相关性</b>：{@code selectByIds} 的返回顺序与传入无关，
 *       因此用命中顺序驱动重组（否则界面上排序就乱了）；</li>
 *   <li><b>孤儿命中要跳过而不是报错</b>：索引里可能残留已删除的切片，
 *       跳过并打日志——这类不一致是数据质量的信号，不该让整次检索失败。</li>
 * </ol>
 */
@Service
public class ChunkSearchServiceImpl implements ChunkSearchService {

    private static final Logger log = LoggerFactory.getLogger(ChunkSearchServiceImpl.class);

    private static final int MAX_TOP_K = 100;
    /** 召回深度上限：太深会把 RRF 稀释成"谁候选多谁赢"。 */
    private static final int MAX_CANDIDATES = 200;
    /**
     * 向量相似度下限。
     *
     * <p>0.25 是 bge-m3 在中文短查询上的经验下限（相关片段通常 0.4~0.7，
     * 无关片段 0.2~0.3）。真正的取值要在 Phase 3 的检索评测里用数据定，
     * 这里先给一个明显偏宽松的值，宁可多召回交给重排处理。
     */
    private static final double MIN_VECTOR_SCORE = 0.25;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeEmbeddingResolver embeddingResolver;
    private final ChunkIndex chunkIndex;
    private final VectorStore vectorStore;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    public ChunkSearchServiceImpl(KnowledgeBaseService knowledgeBaseService,
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

    @Override
    public List<SearchHit> search(Long userId, Long knowledgeBaseId, String query, int topK, SearchMode mode) {
        KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = Math.clamp(topK, 1, MAX_TOP_K);
        int depth = Math.min(MAX_CANDIDATES, limit * 3);
        long start = System.currentTimeMillis();

        List<ChunkSearchHit> bm25Hits = mode.usesBm25()
                ? chunkIndex.search(knowledgeBaseId, query, depth) : List.of();
        List<ChunkSearchHit> vectorHits = mode.usesVector()
                ? vectorSearch(knowledgeBase, query, depth) : List.of();

        List<SearchHit> results = switch (mode) {
            case BM25 -> hydrate(ranked(bm25Hits, "bm25", limit));
            case VECTOR -> hydrate(ranked(vectorHits, "vector", limit));
            case HYBRID -> hydrate(fuse(bm25Hits, vectorHits, limit));
        };

        log.info("检索「{}」mode={} → BM25 {} 条 / 向量 {} 条 → 返回 {} 条，耗时 {} ms",
                query, mode, bm25Hits.size(), vectorHits.size(), results.size(),
                System.currentTimeMillis() - start);
        return results;
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
        // 向量库返回的是 VectorSearchHit（只有 id 与相似度），这里补上来源标识，
        // 让它与 BM25 的命中项统一成一种形状，后续融合与回表都不用分情况处理
        return vectorStore.search(knowledgeBase.getId(), queryVector, depth, MIN_VECTOR_SCORE).stream()
                .map(hit -> new ChunkSearchHit(hit.chunkId(), hit.score(), "vector"))
                .toList();
    }

    /** 按相关性顺序回表补齐正文与文档标题。 */
    private List<SearchHit> hydrate(LinkedHashMap<Long, HitMeta> hits) {
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

        List<SearchHit> results = new ArrayList<>(hits.size());
        hits.forEach((chunkId, meta) -> {
            ChunkPO chunk = chunks.get(chunkId);
            if (chunk == null) {
                log.warn("检索命中了一个已不存在的切片 chunkId={}（索引与数据库不一致）", chunkId);
                return;
            }
            DocumentPO document = documents.get(chunk.getDocumentId());
            results.add(new SearchHit(
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
