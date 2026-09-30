package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.knowledge.rag.retriever.ChunkHydrator.RecalledChunk;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.RrfFusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 召回结果的整理逻辑：单路排名、RRF 融合、跨库归并。
 *
 * <p>这三件事都不该由某个具体的 Retriever 独占：单路排名被向量/关键词两路共用，
 * 融合被混合检索用，跨库归并是所有策略都要面对的（查询允许带多个知识库）。
 * 放在一处的好处是——"融合到底怎么算的"只有一份实现，
 * 不会出现"BM25 那路的排名说明和融合那路的对不上"。
 *
 * <p><b>为什么用 RRF 而不是加权求和</b>：BM25 分数无上界，余弦相似度固定在 [-1,1]，
 * 量纲不同，直接相加等于比较苹果和橘子（权重会退化成"看谁量纲大"）。
 * RRF 只用排名，天然免疫量纲问题，详见 {@link RrfFusion}。
 */
final class RecallSupport {

    private RecallSupport() {
    }

    /**
     * 单路：按原始分数顺序取前 limit 条，带上来源与排名说明。
     *
     * <p>排序不在这里做——上游（ES / pgvector）返回的就是相关性降序，
     * 再排一次只会把"实现认为的顺序"和"我们以为的顺序"搞出分歧。
     *
     * <p>重复 id 只算一次但<b>仍然消耗一个名次</b>：名次是"在原始结果里排第几"，
     * 不是"第几个留下的"，否则解释里显示的排名会与检索实现的实际排名对不上。
     */
    static List<RecalledChunk> ranked(List<ChunkSearchHit> hits, String source, int limit) {
        LinkedHashMap<Long, RecalledChunk> ordered = new LinkedHashMap<>();
        int rank = 1;
        for (ChunkSearchHit hit : hits) {
            if (ordered.size() >= limit) {
                break;
            }
            String label = ("vector".equals(source) ? "向量 #" : "BM25 #") + rank;
            ordered.putIfAbsent(hit.chunkId(),
                    new RecalledChunk(hit.chunkId(), hit.score(), source, List.of(label)));
            rank++;
        }
        return List.copyOf(ordered.values());
    }

    /**
     * 融合：两路各取排名，用 RRF 合成一个分数，并保留每路原始排名用于解释。
     *
     * <p>保留排名的价值在于可解释：界面上显示"BM25 #2 · 向量 #1"，
     * 比只给一个融合总分有用得多——调参时能立刻看出是哪一路把这条顶上来的。
     */
    static List<RecalledChunk> fuse(List<ChunkSearchHit> keywordHits, List<ChunkSearchHit> vectorHits,
                                    int limit) {
        List<RrfFusion.RankedList> lists = List.of(
                new RrfFusion.RankedList("bm25", keywordHits.stream().map(ChunkSearchHit::chunkId).toList()),
                new RrfFusion.RankedList("vector", vectorHits.stream().map(ChunkSearchHit::chunkId).toList()));

        List<RecalledChunk> results = new ArrayList<>();
        for (RrfFusion.FusedHit fused : RrfFusion.fuse(lists, limit)) {
            results.add(new RecalledChunk(fused.chunkId(), fused.score(), "hybrid", fused.matchedBy()));
        }
        return results;
    }

    /**
     * 跨知识库归并。
     *
     * <p><b>单库时原样返回</b>：这是当前检索链路唯一在用的形态，
     * 任何"顺手再排一次序"都会引入与改造前不一致的风险，所以要显式绕开。
     *
     * <p>多库时按分数降序稳定归并，理由是分数可比：同一种召回方式在不同库上是同一量纲
     * （关键词走同一套分词器；向量走同一嵌入模型，且 {@code KnowledgeEmbeddingResolver}
     * 会拒绝模型不一致的库）。分数相同时靠稳定排序保持各自库内的相对顺序。
     */
    static List<RetrievedDocument> merge(List<List<RetrievedDocument>> perKnowledgeBase, int limit) {
        if (perKnowledgeBase.isEmpty()) {
            return List.of();
        }
        if (perKnowledgeBase.size() == 1) {
            return perKnowledgeBase.get(0);
        }
        return perKnowledgeBase.stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                .limit(limit)
                .toList();
    }
}
