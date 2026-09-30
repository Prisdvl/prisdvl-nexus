package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.knowledge.rag.Reranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重排检索测试。
 *
 * <p>重排是整条链路里最贵的一步（一次模型调用），因此这里重点验三件事：
 * ① 只重排前 N 条、其余原样跟在后面（不能因为重排把召回结果丢了）；
 * ② 重排失败时<b>降级而不是失败</b>（保持召回顺序继续回答）；
 * ③ 分数与命中说明如实反映"重排过了"——否则界面上会出现"顺序和分数对不上"。
 */
class RerankedRetrieverTest {

    private HybridRetriever delegate;
    private Reranker reranker;
    private RerankedRetriever retriever;

    @BeforeEach
    void setUp() {
        delegate = mock(HybridRetriever.class);
        reranker = mock(Reranker.class);
        retriever = new RerankedRetriever(delegate, reranker);
    }

    @Test
    @DisplayName("认领 HYBRID_RERANK，名字与召回层区分开（日志里要能看出重排有没有生效）")
    void declaresStrategy() {
        assertThat(retriever.name()).isEqualTo("hybrid-rerank");
        assertThat(retriever.strategies()).containsExactly(RetrievalStrategy.HYBRID_RERANK);
    }

    @Test
    @DisplayName("重排只改前 8 条的顺序，其余保持原相对顺序跟在后面")
    void reranksHeadOnly() {
        List<RetrievedDocument> recalled = documents(10);
        when(delegate.retrieve(any())).thenReturn(recalled);
        // 前 8 条给出的重排分数是升序的 → 排序后这 8 条完全反转，后 2 条不动
        when(reranker.score(any(), anyList())).thenReturn(scoresAscending(8));

        List<RetrievedDocument> result = retriever.retrieve(query());

        assertThat(result).extracting(RetrievedDocument::chunkId)
                .containsExactly(108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L, 109L, 110L);
        // 送进重排的候选只有前 8 条，不是全部
        verify(reranker).score(eq("问题"), argThatSize(8));
    }

    @Test
    @DisplayName("重排后分数如实替换，并追加\"重排 #n\"说明（否则顺序与分数对不上）")
    void replacesScoreAndAppendsExplanation() {
        when(delegate.retrieve(any())).thenReturn(documents(3));
        when(reranker.score(any(), anyList())).thenReturn(List.of(1.0, 9.0, 5.0));

        List<RetrievedDocument> result = retriever.retrieve(query());

        assertThat(result).extracting(RetrievedDocument::chunkId).containsExactly(102L, 103L, 101L);
        assertThat(result).extracting(RetrievedDocument::score).containsExactly(9.0, 5.0, 1.0);
        // 命中说明是追加而不是覆盖：重排后仍然要能看到它原本是靠哪一路召回上来的
        assertThat(result.get(0).matchedBy()).containsExactly("BM25 #2", "重排 #1");
        // 召回层的来源标识保留：重排是在结果之上加的一道工序，不改变它从哪一路来
        assertThat(result.get(0).source()).isEqualTo("hybrid");
    }

    @Test
    @DisplayName("重排失败（分数条数对不上）：保持召回顺序返回，不让整条链路失败")
    void degradesWhenRerankerFails() {
        List<RetrievedDocument> recalled = documents(4);
        when(delegate.retrieve(any())).thenReturn(recalled);
        // LlmReranker 的降级契约就是返回空列表
        when(reranker.score(any(), anyList())).thenReturn(List.of());

        List<RetrievedDocument> result = retriever.retrieve(query());

        assertThat(result).isEqualTo(recalled);
        assertThat(result).extracting(RetrievedDocument::score).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(result.get(0).matchedBy()).as("降级时不该留下\"重排过\"的痕迹")
                .containsExactly("BM25 #1");
    }

    @Test
    @DisplayName("候选不足 2 条时不调用重排（0/1 条没有\"排序\"可言，白花一次模型调用）")
    void skipsRerankForTrivialResults() {
        when(delegate.retrieve(any())).thenReturn(documents(1));

        List<RetrievedDocument> result = retriever.retrieve(query());

        assertThat(result).hasSize(1);
        verify(reranker, never()).score(any(), anyList());
    }

    @Test
    @DisplayName("召回为空时直接返回空，不调用重排")
    void skipsRerankWhenNothingRecalled() {
        when(delegate.retrieve(any())).thenReturn(List.of());

        assertThat(retriever.retrieve(query())).isEmpty();
        verify(reranker, never()).score(any(), anyList());
    }

    @Test
    @DisplayName("重排用的是\"用户原问题\"，不是消解指代后的召回语句")
    void usesRerankQueryForScoring() {
        when(delegate.retrieve(any())).thenReturn(documents(3));
        when(reranker.score(any(), anyList())).thenReturn(List.of(1.0, 2.0, 3.0));

        retriever.retrieve(new RetrievalQuery("上一轮问题 问题", "问题", 1L, 2L, List.of(3L), 5,
                RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD, null, RetrievalStrategy.HYBRID_RERANK));

        verify(reranker).score(eq("问题"), anyList());
    }

    // ------------------------------------------------------------------

    private static RetrievalQuery query() {
        return RetrievalQuery.of("问题", 1L, 2L, 3L, 10, RetrievalStrategy.HYBRID_RERANK);
    }

    private static List<RetrievedDocument> documents(int count) {
        List<RetrievedDocument> documents = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            documents.add(new RetrievedDocument(100L + i, 200L, 3L, "标题 " + i, "file.md",
                    "路径 " + i, null, "正文 " + i, i, "hybrid", List.of("BM25 #" + i)));
        }
        return documents;
    }

    /** 升序分数：排序后名次与召回顺序正好完全相反，一眼就能看出重排到底生效没有。 */
    private static List<Double> scoresAscending(int count) {
        List<Double> scores = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            scores.add((double) i);
        }
        return scores;
    }

    private static List<Reranker.Candidate> argThatSize(int size) {
        return org.mockito.ArgumentMatchers.argThat(candidates -> candidates != null
                && candidates.size() == size);
    }
}
