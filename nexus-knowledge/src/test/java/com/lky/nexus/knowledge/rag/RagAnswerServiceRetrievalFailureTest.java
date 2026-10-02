package com.lky.nexus.knowledge.rag;

import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFailure;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索失败在 RAG 链路里的<b>可见性</b>（Phase 5.5.1）。
 *
 * <p>隔离掉一个失败的知识库是好事（一个库挂了不该让另外两个库的资料一起作废），
 * 但隔离之后的每一步都可能把这件事丢掉，而且每一步丢掉都不报错：
 * <ol>
 *   <li>门面把 failures 扔了 → 上游只知道"命中了 5 条"；</li>
 *   <li>空结果分支返回 {@code PreparedAnswer.none()} → <b>最坏的一种</b>：
 *       用户看到"资料中没有相关内容（知识库尚未导入或未建立索引）"，
 *       而真相是有一个库压根没被搜过；</li>
 *   <li>提示词里不说 → 模型把"这次少了资料"说成"你的笔记里没有"，
 *       用户看到一句斩钉截铁的假否定，而系统提示词第 3 条正好让这句话显得合规。</li>
 * </ol>
 * 所以这一组不看"有没有失败这个概念"，而是逐条钉住"失败到底走到哪儿了"：
 * {@code PreparedAnswer} → {@code userMessage}。
 *
 * <p>最后一条反向钉住：<b>单库路径的 failures 必须恒为空</b>。
 * 单库失败是整批抛异常，不该出现"部分失败"这种状态——它在这里出现就说明
 * 有人在单库路径上套了扇出，那会改变被 {@code ChunkSearchServiceParityTest} 钉住的行为。
 */
class RagAnswerServiceRetrievalFailureTest {

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

        when(knowledgeBaseService.requireAllOwned(eq(USER_ID), anyList()))
                .thenReturn(List.of(knowledgeBase(KB_A), knowledgeBase(KB_B)));
        when(knowledgeBaseService.requireOwned(USER_ID, KB_A)).thenReturn(knowledgeBase(KB_A));
        when(reranker.score(any(), anyList())).thenReturn(List.of());

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
    @DisplayName("部分库失败但其余库有命中：失败带进 PreparedAnswer，资料只来自好的那个库")
    void partialFailureIsCarriedWithTheUsableHits() {
        stubDetailed(hits(KB_A, 101L), List.of(failure(KB_B)));

        PreparedAnswer prepared =
                ragAnswerService.prepareMany(USER_ID, List.of(KB_A, KB_B), "问题", null, List.of());

        assertThat(prepared.empty()).isFalse();
        assertThat(prepared.hasFailures()).isTrue();
        assertThat(prepared.failures()).hasSize(1);
        assertThat(prepared.failures().get(0).knowledgeBaseId()).isEqualTo(KB_B);
        assertThat(prepared.usedHits())
                .as("失败的库一条都不该混进来")
                .allSatisfy(hit -> assertThat(hit.knowledgeBaseId()).isEqualTo(KB_A));
    }

    @Test
    @DisplayName("部分库失败且其余库也没命中：empty=true 但必须同时带上 failures（否则报的是假话）")
    void emptyResultStillCarriesFailures() {
        stubDetailed(List.of(), List.of(failure(KB_B)));

        PreparedAnswer prepared =
                ragAnswerService.prepareMany(USER_ID, List.of(KB_A, KB_B), "问题", null, List.of());

        assertThat(prepared.empty()).isTrue();
        assertThat(prepared.failures())
                .as("没有 failures，调用方只能说『资料中没有相关内容』——一句与事实不符的断言")
                .hasSize(1);
    }

    @Test
    @DisplayName("提示词里点出没搜到的库，并明令不许说『资料中没有』")
    void userMessageTellsTheModelTheRetrievalWasIncomplete() {
        stubDetailed(List.of(), List.of(failure(KB_B)));
        PreparedAnswer prepared =
                ragAnswerService.prepareMany(USER_ID, List.of(KB_A, KB_B), "问题", null, List.of());

        String message = ragAnswerService.userMessage("问题", prepared, List.of());

        assertThat(message)
                .contains("检索范围不完整")
                .contains("「多库B」")
                .contains("ES 连接被拒绝")
                .contains("不要说\"资料中没有\"");
        assertThat(message.indexOf("检索范围不完整"))
                .as("必须在【资料】之前：模型先知道范围不完整，再看残缺的资料")
                .isLessThan(message.indexOf("【资料】"));
    }

    @Test
    @DisplayName("全部库正常但都没命中：不出现任何『检索范围不完整』的措辞")
    void noFailureMeansNoIncompleteNotice() {
        stubDetailed(List.of(), List.of());

        PreparedAnswer prepared =
                ragAnswerService.prepareMany(USER_ID, List.of(KB_A, KB_B), "问题", null, List.of());

        assertThat(prepared.hasFailures()).isFalse();
        assertThat(ragAnswerService.userMessage("问题", prepared, List.of()))
                .doesNotContain("检索范围不完整");
    }

    @Test
    @DisplayName("单库路径恒无 failures：单库失败是整批抛异常，不存在『部分失败』这种状态")
    void singleKnowledgeBaseNeverReportsPartialFailure() {
        when(chunkSearchService.search(eq(USER_ID), eq(KB_A), anyString(), any(), any()))
                .thenReturn(hits(KB_A, 101L));

        PreparedAnswer prepared = ragAnswerService.prepare(USER_ID, KB_A, "问题", null, List.of());

        assertThat(prepared.failures()).isEmpty();
        assertThat(prepared.hasFailures()).isFalse();
    }

    // ------------------------------------------------------------------

    private void stubDetailed(List<ChunkSearchService.SearchHit> hits, List<RetrievalFailure> failures) {
        when(chunkSearchService.searchManyDetailed(eq(USER_ID), anyList(), anyString(), any(), any(),
                eq(RetrievalFilters.none())))
                .thenReturn(new ChunkSearchService.MultiKbSearchResult(hits, failures));
    }

    private static RetrievalFailure failure(Long kbId) {
        return new RetrievalFailure(kbId, "多库B", "ES 连接被拒绝");
    }

    private static KnowledgeBasePO knowledgeBase(Long id) {
        KnowledgeBasePO entity = new KnowledgeBasePO();
        entity.setId(id);
        entity.setUserId(USER_ID);
        entity.setWorkspaceId(9L);
        entity.setName("多库B");
        entity.setRetrievalTopK(5);
        entity.setSimilarityThreshold(0.25);
        return entity;
    }

    private static List<ChunkSearchService.SearchHit> hits(Object... kbIdAndChunkIdPairs) {
        List<ChunkSearchService.SearchHit> hits = new ArrayList<>();
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
