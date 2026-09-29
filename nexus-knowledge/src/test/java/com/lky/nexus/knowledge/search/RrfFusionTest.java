package com.lky.nexus.knowledge.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合测试（纯逻辑，不依赖 ES / 向量库 / 模型）。
 *
 * <p>融合是"混合检索到底比单路好多少"的关键，也最容易出隐蔽错误——
 * 比如把不可比的分数直接相加、或者某一路的候选被整段丢掉。
 * 因此这里用可手算的小例子把行为钉死。
 */
class RrfFusionTest {

    @Test
    @DisplayName("两路都排前的候选胜出（多路共识 > 单路第一）")
    void consensusWins() {
        // A：两路都在前列；B：只在 BM25 排第一；C：只在向量排第一
        var fused = RrfFusion.fuse(List.of(
                new RrfFusion.RankedList("bm25", List.of(2L, 1L)),      // B 第 1，A 第 2
                new RrfFusion.RankedList("vector", List.of(3L, 1L))     // C 第 1，A 第 2
        ), 10);

        assertThat(fused).extracting(RrfFusion.FusedHit::chunkId)
                .as("两路都命中的候选应排第一")
                .startsWith(1L);
        assertThat(fused.get(0).matchedBy()).containsExactly("BM25 #2", "向量 #2");
    }

    @Test
    @DisplayName("RRF 只用排名不用分数：两路分数尺度不可比也不影响结果")
    void usesRanksNotScores() {
        // 单路结果的"分数"根本没有传进 RRF（接口只收 chunkId 列表），
        // 这本身就是设计约束：融合不可能被某一路的量纲带偏
        var fused = RrfFusion.fuse(List.of(
                new RrfFusion.RankedList("bm25", List.of(10L, 11L)),
                new RrfFusion.RankedList("vector", List.of(10L, 12L))
        ), 10);

        assertThat(fused.get(0).chunkId()).isEqualTo(10L);
        // 10 号在两路都排第 1：1/61 + 1/61
        assertThat(fused.get(0).score()).isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("去重：同一候选出现在多路时只输出一条，并合并来源说明")
    void deduplicatesAcrossLists() {
        var fused = RrfFusion.fuse(List.of(
                new RrfFusion.RankedList("bm25", List.of(7L, 7L)),   // 故意重复，模拟上游异常
                new RrfFusion.RankedList("vector", List.of(7L))
        ), 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).matchedBy()).containsExactly("BM25 #1", "向量 #1");
    }

    @Test
    @DisplayName("某一路为空时退化为另一路的排名（混合检索不会因为单路不可用而失效）")
    void degradesGracefullyWhenOneSourceIsEmpty() {
        var fused = RrfFusion.fuse(List.of(
                new RrfFusion.RankedList("bm25", List.of(5L, 6L)),
                new RrfFusion.RankedList("vector", List.of())
        ), 10);

        assertThat(fused).extracting(RrfFusion.FusedHit::chunkId).containsExactly(5L, 6L);
        assertThat(fused.get(0).vectorRank()).isNull();
    }

    @Test
    @DisplayName("topK 截断；分数相同时按 chunkId 稳定排序（同一查询两次结果一致）")
    void truncatesAndSortsStably() {
        var lists = List.of(
                new RrfFusion.RankedList("bm25", List.of(20L, 21L)),
                new RrfFusion.RankedList("vector", List.of(22L, 23L)));

        var first = RrfFusion.fuse(lists, 2);
        var second = RrfFusion.fuse(lists, 2);

        assertThat(first).hasSize(2);
        assertThat(first).isEqualTo(second);
        // 四者分数完全相同（都只在一路的第 1 或第 2 位），因此按 id 稳定排序
        assertThat(first).extracting(RrfFusion.FusedHit::chunkId).containsExactly(20L, 22L);
    }
}
