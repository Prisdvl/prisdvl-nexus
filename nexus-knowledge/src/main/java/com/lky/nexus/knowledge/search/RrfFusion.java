package com.lky.nexus.knowledge.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）。
 *
 * <p><b>为什么不能把两路分数直接相加</b>：BM25 的分数是无上界的（同一次查询里
 * 命中几条、词频多高都会影响），而余弦相似度固定在 [-1,1]。两者量纲完全不同，
 * 直接加权求和等于在比较苹果和橘子，权重也就变成了"看谁的量纲大"。
 *
 * <p>RRF 的做法是<b>只用排名、不用分数</b>：每个候选取 {@code 1/(k + rank)} 之和，
 * k 通常取 60（来自原论文的经验值——它压平了前几名之间的差距，
 * 让"多路都排前"比"某一路排第一"更重要）。因此它天然不需要归一化，
 * 也不怕某一路分数尺度变化。
 */
public final class RrfFusion {

    /** 平滑常数：越大越"民主"（各路排名差距被压得越平）。 */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合多路排名。
     *
     * @param rankedLists 各路结果（顺序即排名，从第 1 名开始）
     * @param topK        返回条数
     * @param k           平滑常数，见 {@link #DEFAULT_K}
     */
    public static List<FusedHit> fuse(List<RankedList> rankedLists, int topK, int k) {
        Map<Long, FusedHitBuilder> byChunkId = new LinkedHashMap<>();
        for (RankedList list : rankedLists) {
            // 同一路内的重复项只算一次（取最好的排名）。
            // 正常情况下 ES / 向量库不会返回重复 id，但融合是公共逻辑，
            // 不该假设上游永远干净——重复计入会让某个候选的分数凭空翻倍。
            Set<Long> seenInList = new HashSet<>();
            int rank = 1;
            for (Long chunkId : list.chunkIds()) {
                if (!seenInList.add(chunkId)) {
                    continue;
                }
                byChunkId.computeIfAbsent(chunkId, FusedHitBuilder::new)
                        .add(list.source(), rank, k);
                rank++;
            }
        }
        return byChunkId.values().stream()
                .map(FusedHitBuilder::build)
                .sorted(Comparator.comparingDouble(FusedHit::score).reversed()
                        // 分数相同时按 chunkId 稳定排序，避免同一查询两次结果顺序不同
                        .thenComparing(FusedHit::chunkId))
                .limit(topK)
                .toList();
    }

    public static List<FusedHit> fuse(List<RankedList> rankedLists, int topK) {
        return fuse(rankedLists, topK, DEFAULT_K);
    }

    /**
     * 一路排名。
     *
     * @param source   来源标识（bm25 / vector）
     * @param chunkIds 按相关性降序的切片 id
     */
    public record RankedList(String source, List<Long> chunkIds) {
    }

    /**
     * 融合后的命中项。
     *
     * <p>保留每路的原始排名：《为什么这条排第一》必须能回答。
     * 界面上直接展示"BM25 #2 · 向量 #1"比只给一个总分有用得多。
     */
    public record FusedHit(Long chunkId, double score, Integer bm25Rank, Integer vectorRank) {

        /** 命中的来源明细，如 {@code ["BM25 #2", "向量 #1"]}。 */
        public List<String> matchedBy() {
            List<String> matched = new ArrayList<>(2);
            if (bm25Rank != null) {
                matched.add("BM25 #" + bm25Rank);
            }
            if (vectorRank != null) {
                matched.add("向量 #" + vectorRank);
            }
            return matched;
        }
    }

    private static final class FusedHitBuilder {
        private final Long chunkId;
        private double score;
        private Integer bm25Rank;
        private Integer vectorRank;

        private FusedHitBuilder(Long chunkId) {
            this.chunkId = chunkId;
        }

        private void add(String source, int rank, int k) {
            score += 1.0 / (k + rank);
            if ("vector".equals(source)) {
                vectorRank = rank;
            } else {
                bm25Rank = rank;
            }
        }

        private FusedHit build() {
            return new FusedHit(chunkId, score, bm25Rank, vectorRank);
        }
    }
}
