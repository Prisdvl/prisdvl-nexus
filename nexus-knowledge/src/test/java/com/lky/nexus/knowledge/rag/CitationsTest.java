package com.lky.nexus.knowledge.rag;

import com.lky.nexus.knowledge.service.ChunkSearchService.SearchHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 引用构造（{@link Citations}）。
 *
 * <p>它是<b>三条链路共用</b>的一处（知识库问答同步 / {@code /ask/stream} / 会话流式），
 * 因此这里钉的不是"某个页面显示得对不对"，而是"同一个编号在三条路上指的是不是同一段资料"。
 * 一旦分叉成两套构造，界面上同一份历史里的 {@code [2]} 会指向不同的片段——
 * 而这种错误不会报错，只会让用户看到一份对不上的出处。
 *
 * <p>另一个必须钉住的字段是 {@code knowledgeBaseId}（Phase 5.5 起多库）：
 * 会话可以同时绑"产品手册 + 内部 FAQ"，没有这个字段，"这段资料哪来的"就没有答案。
 */
class CitationsTest {

    private static SearchHit hit(long chunkId, long kbId, String title, String heading, double score) {
        return new SearchHit(chunkId, chunkId * 10, title, heading,
                "正文 " + chunkId, score, "hybrid", List.of("bm25"), kbId);
    }

    @Test
    @DisplayName("编号即列表位置，出处与正文逐字段带过来")
    void buildsIndexAndCarriesKnowledgeBaseId() {
        List<RagAnswer.RagCitation> citations = Citations.build(
                List.of(hit(9001L, 1001L, "Java 笔记", "缓存 › 击穿", 0.91),
                        hit(9002L, 1002L, "运维手册", "故障处置 › 缓存雪崩", 0.83)),
                "两种做法都对[1][2]。");

        assertThat(citations)
                .extracting(RagAnswer.RagCitation::index, RagAnswer.RagCitation::knowledgeBaseId,
                        RagAnswer.RagCitation::chunkId, RagAnswer.RagCitation::documentTitle,
                        RagAnswer.RagCitation::headingPath, RagAnswer.RagCitation::score)
                .containsExactly(
                        tuple(1, 1001L, 9001L, "Java 笔记", "缓存 › 击穿", 0.91),
                        tuple(2, 1002L, 9002L, "运维手册", "故障处置 › 缓存雪崩", 0.83));
        assertThat(citations.get(0).snippet()).isEqualTo("正文 9001");
    }

    @Test
    @DisplayName("referenced 认得出 [1] / [1,2] / [1][2] 三种写法，没被引用的也保留")
    void marksReferencedForAllCitationShapes() {
        List<SearchHit> hits = List.of(
                hit(1L, 1001L, "A", null, 0.9),
                hit(2L, 1001L, "B", null, 0.8),
                hit(3L, 1001L, "C", null, 0.7));

        assertThat(Citations.build(hits, "见 [1] 与 [3]。"))
                .extracting(RagAnswer.RagCitation::referenced)
                .containsExactly(true, false, true);
        assertThat(Citations.build(hits, "见 [1,3]。"))
                .extracting(RagAnswer.RagCitation::referenced)
                .containsExactly(true, false, true);
        assertThat(Citations.build(hits, "见 [1][3]。"))
                .extracting(RagAnswer.RagCitation::referenced)
                .containsExactly(true, false, true);
    }

    /**
     * 一条都没引用时也必须返回全部片段（{@code referenced = false}）。
     *
     * <p>这正是"模型没用资料"这个结论的唯一证据：把未引用的片段丢掉，
     * 界面上就只剩"有来源"，用户看不出模型其实在自说自话。
     */
    @Test
    @DisplayName("回答里一个引用都没有时，片段照样返回且全部标记为未引用")
    void keepsUnreferencedHitsSoTheGapIsVisible() {
        List<RagAnswer.RagCitation> citations = Citations.build(
                List.of(hit(1L, 1001L, "A", null, 0.9)), "我直接回答了，没提资料。");

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).referenced()).isFalse();
    }

    @Test
    @DisplayName("空片段列表 → 空引用列表（不抛异常，也不返回 null）")
    void emptyHitsYieldEmptyCitations() {
        assertThat(Citations.build(List.of(), "没有资料的回答")).isEmpty();
        assertThat(Citations.build(List.of(), null)).isEmpty();
    }

    @Test
    @DisplayName("多次召回合并：按 chunkId 去重、编号连续，referenced 取各组的并集")
    void mergesMultipleGroupsByDeduplicatingAndRenumbering() {
        // 第一次工具调用拿到 A、B；第二次换了个关键词，拿到 B（重复）与 C。
        // 两次的编号各自从 1 开始：A=[1] B=[2]，B=[1] C=[2]
        List<SearchHit> firstCall = List.of(hit(1L, 1001L, "A", null, 0.9), hit(2L, 1001L, "B", null, 0.8));
        List<SearchHit> secondCall = List.of(hit(2L, 1001L, "B", null, 0.8), hit(3L, 1002L, "C", null, 0.7));

        List<RagAnswer.RagCitation> merged = Citations.merge(List.of(firstCall, secondCall), "结论见 [1]。");

        // [1] 在两次召回里分别指向 A 和 B —— 光看正文分不出模型指的是哪一个，
        // 因此两段都算"被摆在引用编号上"；C 从未出现在被引用的编号上，才是真的未引用
        assertThat(merged)
                .extracting(RagAnswer.RagCitation::index, RagAnswer.RagCitation::chunkId,
                        RagAnswer.RagCitation::documentTitle, RagAnswer.RagCitation::referenced)
                .containsExactly(
                        tuple(1, 1L, "A", true),
                        tuple(2, 2L, "B", true),
                        tuple(3, 3L, "C", false));
        assertThat(merged).extracting(RagAnswer.RagCitation::knowledgeBaseId)
                .containsExactly(1001L, 1001L, 1002L);
    }

    @Test
    @DisplayName("单组时合并是恒等变换：编号与回答里的 [n] 严格对齐（RAG 那条路）")
    void mergeIsIdentityForASingleGroup() {
        List<SearchHit> hits = List.of(hit(1L, 1001L, "A", null, 0.9), hit(2L, 1001L, "B", null, 0.8));

        assertThat(Citations.merge(List.of(hits), "见 [2]。"))
                .isEqualTo(Citations.build(hits, "见 [2]。"));
    }

    @Test
    @DisplayName("没有召回（空分组 / 空列表）→ 空引用列表")
    void mergeWithoutRetrievalYieldsEmptyList() {
        assertThat(Citations.merge(List.of(), "没有检索的回答")).isEmpty();
        assertThat(Citations.merge(List.of(List.of(), List.of()), "两次都没搜到")).isEmpty();
    }

    @Test
    @DisplayName("withIndex 只换编号，其余字段（含 referenced）原样保留")
    void withIndexRenumbersAndKeepsEverythingElse() {
        RagAnswer.RagCitation original = Citations.build(
                List.of(hit(9001L, 1001L, "Java 笔记", "缓存 › 击穿", 0.91)), "见 [1]。").get(0);

        RagAnswer.RagCitation renumbered = original.withIndex(7);

        assertThat(renumbered.index()).isEqualTo(7);
        assertThat(renumbered)
                .extracting(RagAnswer.RagCitation::knowledgeBaseId, RagAnswer.RagCitation::chunkId,
                        RagAnswer.RagCitation::documentId, RagAnswer.RagCitation::documentTitle,
                        RagAnswer.RagCitation::headingPath, RagAnswer.RagCitation::snippet,
                        RagAnswer.RagCitation::score, RagAnswer.RagCitation::referenced)
                .containsExactly(original.knowledgeBaseId(), original.chunkId(), original.documentId(),
                        original.documentTitle(), original.headingPath(), original.snippet(),
                        original.score(), original.referenced());
    }
}
