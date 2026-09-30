package com.lky.nexus.knowledge.rag;

import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.json.JsonMapper;

/**
 * RAG 准备阶段：重排到底成功没有，以及检索参数交给谁定（Phase 3.2）。
 *
 * <p>两个必须钉住的点，都来自"参数不再由问答层写死"这一改动：
 * <ol>
 *   <li><b>重排成功的判据不能跟候选条数挂钩</b>。原判据是 {@code scores.size() == 8}，
 *       它假定候选永远够 8 条；而候选条数现在来自知识库的 {@code retrieval_top_k}（默认 5）。
 *       于是"按库调小 topK"会让重排明明做成了却自报 {@code reranked=false}——
 *       界面上写着"未重排"，顺序其实已经变过，看到的顺序和说明对不上；</li>
 *   <li><b>topK 传 null 必须一路传下去</b>，而不是在问答层补一个写死的数：
 *       否则知识库的检索参数对问答（也就是产品主路径）永远无效。</li>
 * </ol>
 */
class RagAnswerServiceRerankFlagTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_ID = 1001L;

    private ChunkSearchService chunkSearchService;
    private Reranker reranker;
    private RagAnswerServiceImpl ragAnswerService;

    @BeforeEach
    void setUp() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        chunkSearchService = mock(ChunkSearchService.class);
        reranker = mock(Reranker.class);

        KnowledgeBasePO knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setWorkspaceId(9L);
        knowledgeBase.setRetrievalTopK(5);
        knowledgeBase.setSimilarityThreshold(0.25);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);

        ragAnswerService = new RagAnswerServiceImpl(
                knowledgeBaseService,
                chunkSearchService,
                reranker,
                new ContextAssembler(),
                mock(ChatClientFactory.class),
                mock(QaRecordMapper.class),
                mock(JsonMapper.class),
                new ObservabilityRegistry(),
                2000);
    }

    @Test
    @DisplayName("候选 5 条、重排给了 5 个分数：必须报\"已重排\"（不能因为不到 8 条就自报降级）")
    void rerankedIsTrueEvenWhenCandidatesFewerThanEight() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(hits(5));
        when(reranker.score(any(), anyList())).thenReturn(scores(5));

        PreparedAnswer prepared = ragAnswerService.prepare(USER_ID, KB_ID, "问题", null, List.of());

        assertThat(prepared.reranked()).isTrue();
        assertThat(prepared.empty()).isFalse();
    }

    @Test
    @DisplayName("重排降级（返回空）：报\"未重排\"，且资料顺序保持召回顺序")
    void rerankedIsFalseWhenRerankerDegrades() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(hits(5));
        when(reranker.score(any(), anyList())).thenReturn(List.of());

        PreparedAnswer prepared = ragAnswerService.prepare(USER_ID, KB_ID, "问题", null, List.of());

        assertThat(prepared.reranked()).isFalse();
        assertThat(prepared.usedHits()).extracting(ChunkSearchService.SearchHit::chunkId)
                .containsExactly(101L, 102L, 103L, 104L, 105L);
    }

    @Test
    @DisplayName("候选超过重排上限 8 条：只重排前 8 条，其余按原相对顺序跟在后面")
    void reranksHeadOnlyAndKeepsTail() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(hits(12));
        when(reranker.score(any(), anyList())).thenReturn(scores(8));

        PreparedAnswer prepared = ragAnswerService.prepare(USER_ID, KB_ID, "问题", null, List.of());

        assertThat(prepared.reranked()).isTrue();
        assertThat(prepared.usedHits()).extracting(ChunkSearchService.SearchHit::chunkId)
                .containsExactly(108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L, 109L, 110L, 111L, 112L);
    }

    @Test
    @DisplayName("没检索到资料：不调用重排，直接返回\"无资料\"")
    void skipsRerankWhenNothingRetrieved() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(List.of());

        PreparedAnswer prepared = ragAnswerService.prepare(USER_ID, KB_ID, "问题", null, List.of());

        assertThat(prepared.empty()).isTrue();
        assertThat(prepared.reranked()).isFalse();
        verify(reranker, never()).score(any(), anyList());
    }

    @Test
    @DisplayName("topK 传 null（调用方没指定）就原样传给检索层——由知识库配置决定，问答层不补数字")
    void passesNullTopKDownToRetrieval() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(hits(1));
        when(reranker.score(any(), anyList())).thenReturn(scores(1));

        ragAnswerService.prepare(USER_ID, KB_ID, "问题", null, List.of());

        verify(chunkSearchService).search(eq(USER_ID), eq(KB_ID), eq("问题"), isNull(), eq(SearchMode.HYBRID));
    }

    // ------------------------------------------------------------------

    /** 升序分数：重排后前 8 条正好反转，一眼能看出"重排真的生效了"。 */
    private static List<Double> scores(int count) {
        List<Double> scores = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            scores.add((double) i);
        }
        return scores;
    }

    /**
     * 造 {@code count} 条互不相同的命中。
     *
     * <p>正文刻意写成"…编号 N。"而不是"正文 N"：{@code ContextAssembler} 会做
     * 重叠切片去重（"某段被另一段包含"就丢掉），而"正文 1"正好是"正文 12"的子串，
     * 会被判定成重复片段——这是它的正常职责，但会让本用例的条数断言莫名少几条。
     * 编号后面的句号正是为了断开这种前缀关系。
     */
    private static List<ChunkSearchService.SearchHit> hits(int count) {
        List<ChunkSearchService.SearchHit> hits = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            hits.add(new ChunkSearchService.SearchHit(100L + i, 200L, "缓存笔记",
                    "缓存 › 第 " + i + " 节",
                    "这是一段独一无二的资料内容，编号 " + i + "。",
                    10.0 - i, "hybrid", List.of("BM25 #" + i)));
        }
        return hits;
    }
}
