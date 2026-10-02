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
 *
 * <p><b>可见性</b>：Phase 5.5 起 {@link #merge} 被 {@code ChunkSearchServiceImpl}
 * 直接调用（多库检索由门面逐库扇出后再归并），因此这个类与那个方法改为 public。
 * 其余方法仍是包内可见——它们只服务于 Retriever 家族内部。
 */
public final class RecallSupport {

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
     * <p><b>单库时原样返回</b>：这是一条被 {@code ChunkSearchServiceParityTest} 钉死的路径，
     * 任何"顺手再排一次序"都会引入与改造前不一致的风险，所以要显式绕开。
     *
     * <p>多库时按分数降序归并，分数相同时靠稳定排序保持各自库内的相对顺序，
     * 最后按 {@code limit} 截断（各库召回深度之和通常远大于它）。
     *
     * <p><b>分数可比性是有条件的——这里只保证"同一种召回方式的分"大体可比</b>：
     * <ul>
     *   <li><b>向量分真的可比</b>：余弦相似度是绝对量纲，且所有库都必须与同一个全局嵌入配置一致
     *       （{@code KnowledgeEmbeddingResolver} 逐个库比对，不一致直接拒绝），
     *       因此各库的向量落在同一个空间里，分数量纲一致；</li>
     *   <li><b>BM25 分不可比</b>：同一套分词器只保证切词一致，而 BM25 分数还取决于
     *       各索引自己的词频统计（IDF、文档数、平均长度）。两个库的 "0.9 分" 不是一回事，
     *       库里文档少、词罕见的那一方会系统性偏高；</li>
     *   <li><b>RRF 分只是近似可比</b>：它由排名算出，而各库的候选深度取决于各自的
     *       {@code retrieval_top_k}，深度不同则同一个排名对应的分数贡献不同。</li>
     * </ul>
     *
     * <p>取舍：RAG 与 Agent 实际只走 {@code HYBRID}（库内先 RRF，再跨库归并），
     * 此时比较的是"库内融合名次"的折算分，偏差有界且不会让某个库整体霸榜。
     * 若将来要单开 BM25 / VECTOR 的多库模式，这里需要改成<b>按库归一化</b>
     * （例如折算成库内百分位）再归并，而不是现在这样直接比原始分。
     */
    public static List<RetrievedDocument> merge(List<List<RetrievedDocument>> perKnowledgeBase, int limit) {
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
