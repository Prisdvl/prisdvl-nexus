package com.lky.nexus.knowledge.rag;

import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.json.JsonMapper;

/**
 * RAG 准备阶段的多库分派（Phase 5.5）。
 *
 * <p>这一组只钉两件事，但两件都容易在重构中"顺手"丢掉：
 * <ol>
 *   <li><b>多库必须真的走多库检索</b>——传两个库却调单库接口（或者反过来），
 *       症状是"绑了两个库但只搜到一个"，而接口 200、界面无异常；</li>
 *   <li><b>单库必须仍然走单库检索</b>——单库那条路径被
 *       {@code ChunkSearchServiceParityTest} 逐字段钉着，让 {@code prepare} 改走
 *       {@code searchMany} 就等于把它挪出护栏。这里用"有没有调用 searchMany"把这条守住。</li>
 * </ol>
 *
 * <p>顺带验一条引用相关的不变式：多库时进入资料块的命中<b>各自带着自己的库 id</b>，
 * 否则"这条引用出自哪个库"在回答里就无从标注。
 */
class RagAnswerServicePrepareManyTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_A = 1001L;
    private static final Long KB_B = 1002L;

    private KnowledgeBaseService knowledgeBaseService;
    private ChunkSearchService chunkSearchService;
    private Reranker reranker;
    private RagAnswerServiceImpl ragAnswerService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        chunkSearchService = mock(ChunkSearchService.class);
        reranker = mock(Reranker.class);

        when(knowledgeBaseService.requireOwned(USER_ID, KB_A)).thenReturn(knowledgeBase(KB_A));
        when(knowledgeBaseService.requireAllOwned(eq(USER_ID), anyList()))
                .thenReturn(List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)));

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
    @DisplayName("两个库：走 searchMany，且把两个库 id 都传下去（不是取第一个）")
    void multipleKnowledgeBasesDispatchToSearchMany() {
        when(chunkSearchService.searchMany(eq(USER_ID), anyList(), anyString(), any(), any()))
                .thenReturn(hits(KB_A, 101L, KB_B, 201L));
        when(reranker.score(any(), anyList())).thenReturn(List.of());

        PreparedAnswer prepared =
                ragAnswerService.prepareMany(USER_ID, List.of(KB_A, KB_B), "问题", null, List.of());

        verify(chunkSearchService).searchMany(eq(USER_ID), eq(List.of(KB_A, KB_B)), eq("问题"),
                eq(null), eq(SearchMode.HYBRID));
        verify(chunkSearchService, never()).search(any(), any(), anyString(), any(), any());
        assertThat(prepared.empty()).isFalse();
    }

    @Test
    @DisplayName("单库：仍走单库 search，绝不改走 searchMany（那条路径被一致性测试钉着）")
    void singleKnowledgeBaseStillUsesSingleSearch() {
        when(chunkSearchService.search(eq(USER_ID), eq(KB_A), anyString(), any(), any()))
                .thenReturn(hits(KB_A, 101L));
        when(reranker.score(any(), anyList())).thenReturn(List.of());

        ragAnswerService.prepare(USER_ID, KB_A, "问题", null, List.of());

        verify(chunkSearchService).search(eq(USER_ID), eq(KB_A), eq("问题"), eq(null),
                eq(SearchMode.HYBRID));
        verify(chunkSearchService, never()).searchMany(any(), anyList(), anyString(), any(), any());
    }

    @Test
    @DisplayName("多库命中：每条进入资料块的片段各自带着自己的库 id（引用才标得出处）")
    void usedHitsKeepTheirOwnKnowledgeBaseId() {
        when(chunkSearchService.searchMany(eq(USER_ID), anyList(), anyString(), any(), any()))
                .thenReturn(hits(KB_A, 101L, KB_B, 201L));
        when(reranker.score(any(), anyList())).thenReturn(List.of());

        PreparedAnswer prepared =
                ragAnswerService.prepareMany(USER_ID, List.of(KB_A, KB_B), "问题", null, List.of());

        assertThat(prepared.usedHits())
                .extracting(ChunkSearchService.SearchHit::knowledgeBaseId)
                .containsExactly(KB_A, KB_B);
    }

    @Test
    @DisplayName("有一个库不合格：3001 直接抛出去，不做部分成功（不查其余库）")
    void unownedKnowledgeBaseFailsWholeBatch() {
        when(knowledgeBaseService.requireAllOwned(USER_ID, List.of(KB_A, 9999L)))
                .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在或无权访问：id=9999"));

        assertThatThrownBy(() -> ragAnswerService.prepareMany(
                USER_ID, List.of(KB_A, 9999L), "问题", null, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("9999");
        verify(chunkSearchService, never()).searchMany(any(), anyList(), anyString(), any(), any());
    }

    @Test
    @DisplayName("空问题：多库路径同样直接返回空资料，不检索（与单库一致）")
    void blankQuestionShortCircuits() {
        PreparedAnswer prepared =
                ragAnswerService.prepareMany(USER_ID, List.of(KB_A, KB_B), "  ", null, List.of());

        assertThat(prepared.empty()).isTrue();
        verify(chunkSearchService, never()).searchMany(any(), anyList(), anyString(), any(), any());
    }

    // ------------------------------------------------------------------

    private static KnowledgeBasePO knowledgeBase(Long id) {
        KnowledgeBasePO entity = new KnowledgeBasePO();
        entity.setId(id);
        entity.setUserId(USER_ID);
        entity.setWorkspaceId(9L);
        entity.setRetrievalTopK(5);
        entity.setSimilarityThreshold(0.25);
        return entity;
    }

    /** 按 (库, 起始 chunkId) 成对给出命中：正文各不相同，免得被上下文去重塌成一条。 */
    private static List<ChunkSearchService.SearchHit> hits(Object... kbIdAndChunkIdPairs) {
        List<ChunkSearchService.SearchHit> hits = new java.util.ArrayList<>();
        for (int i = 0; i < kbIdAndChunkIdPairs.length; i += 2) {
            Long kbId = (Long) kbIdAndChunkIdPairs[i];
            Long chunkId = (Long) kbIdAndChunkIdPairs[i + 1];
            hits.add(new ChunkSearchService.SearchHit(chunkId, 900L, "笔记", "缓存 › 第 1 节",
                    "来自知识库 " + kbId + " 的独有资料，编号 " + chunkId + "。", 1.0 - i * 0.01,
                    "hybrid", List.of("BM25 #1"), kbId));
        }
        return hits;
    }
}
