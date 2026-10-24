package com.lky.nexus.app.search;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.Reranker;
import com.lky.nexus.knowledge.rag.retriever.ExplainRecaller;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFailure;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨库统一检索（§4.2）的接口测试。
 *
 * <p>两条 mock 边界，都是为了把"编排"与"检索"分开测：
 * <ul>
 *   <li>{@link ChunkSearchService} 被替身：本用例要验证的是"多个库的结果怎么合、失败的库怎么报"，
 *       而不是 ES / pgvector 能不能连上（测试环境两者都是关的）；</li>
 *   <li>{@link ExplainRecaller} 被替身：explain 的价值全在名次与分数的算术上，
 *       两路原始命中喂进去以后，后面的 RRF 融合、全局排序、重排必须是真的。</li>
 * </ul>
 * {@link Reranker} 同样是替身——真实现要调模型，测试不联网。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchApiTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @MockitoBean
    private ChunkSearchService chunkSearchService;

    @MockitoBean
    private ExplainRecaller explainRecaller;

    @MockitoBean
    private Reranker reranker;

    @Test
    @DisplayName("跨库检索：命中带上来源库名、顺序与降级列表原样透出")
    void searchAcrossTwoKnowledgeBases() throws Exception {
        String token = registerAndLogin("search-two");
        long kbA = createKb(token, "Java 笔记");
        long kbB = createKb(token, "Redis 源码");

        when(chunkSearchService.searchManyDetailed(anyLong(), anyList(), anyString(), any(), any(), any()))
                .thenReturn(new ChunkSearchService.MultiKbSearchResult(
                        List.of(hit(11L, 1.2, kbA, "Java 并发"), hit(12L, 0.9, kbB, "Redis 持久化"), hit(13L, 0.5, kbA, "GC 调优")),
                        List.of()));

        mockMvc.perform(post("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"redis","mode":"HYBRID","topK":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hits.length()").value(3))
                .andExpect(jsonPath("$.data.hits[0].chunkId").value("11"))
                .andExpect(jsonPath("$.data.hits[0].kbName").value("Java 笔记"))
                .andExpect(jsonPath("$.data.hits[1].kbName").value("Redis 源码"))
                .andExpect(jsonPath("$.data.hits[0].source").value("hybrid"))
                .andExpect(jsonPath("$.data.degradedKbs.length()").value(0))
                // 有命中时不该给空原因，否则前端会同时渲染"结果列表"和"没找到"
                .andExpect(jsonPath("$.data.emptyReason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.tookMs").isNumber());

        // kbIds 为空 = 用户的全部库：两个库都要在范围里，且顺序不该由调用方猜
        verify(chunkSearchService).searchManyDetailed(anyLong(),
                argThat(ids -> ids != null && ids.size() == 2 && ids.containsAll(List.of(kbA, kbB))),
                eq("redis"), eq(10), eq(SearchMode.HYBRID), any());
    }

    @Test
    @DisplayName("单个库失败：其它库的结果照常返回，失败的库进 degradedKbs 而不是整体 500")
    void singleKnowledgeBaseFailureDegrades() throws Exception {
        String token = registerAndLogin("search-degrade");
        long kbA = createKb(token, "可用库");
        long kbB = createKb(token, "炸了的库");

        when(chunkSearchService.searchManyDetailed(anyLong(), anyList(), anyString(), any(), any(), any()))
                .thenReturn(new ChunkSearchService.MultiKbSearchResult(
                        List.of(hit(21L, 1.0, kbA, "可用文档")),
                        List.of(new RetrievalFailure(kbB, "炸了的库", "索引不可用"))));

        mockMvc.perform(post("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"redis\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hits.length()").value(1))
                .andExpect(jsonPath("$.data.degradedKbs.length()").value(1))
                .andExpect(jsonPath("$.data.degradedKbs[0].kbId").value(String.valueOf(kbB)))
                .andExpect(jsonPath("$.data.degradedKbs[0].kbName").value("炸了的库"))
                .andExpect(jsonPath("$.data.degradedKbs[0].reason").value("索引不可用"));
    }

    @Test
    @DisplayName("显式点名的禁用库：不参与检索，但如实回报为降级")
    void disabledKnowledgeBaseIsSkippedAndReported() throws Exception {
        String token = registerAndLogin("search-disabled");
        long kbA = createKb(token, "启用的库");
        long kbB = createKb(token, "被禁用的库");
        KnowledgeBasePO disabled = knowledgeBaseMapper.selectById(kbB);
        disabled.setStatus("DISABLED");
        knowledgeBaseMapper.updateById(disabled);

        when(chunkSearchService.searchManyDetailed(anyLong(), anyList(), anyString(), any(), any(), any()))
                .thenReturn(new ChunkSearchService.MultiKbSearchResult(
                        List.of(hit(31L, 1.0, kbA, "启用的文档")), List.of()));

        mockMvc.perform(post("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"redis","kbIds":[%d,%d]}
                                """.formatted(kbA, kbB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(1))
                .andExpect(jsonPath("$.data.degradedKbs.length()").value(1))
                .andExpect(jsonPath("$.data.degradedKbs[0].kbId").value(String.valueOf(kbB)))
                .andExpect(jsonPath("$.data.degradedKbs[0].reason").value(org.hamcrest.Matchers.containsString("禁用")));

        // 禁用的库不该被送进检索链路：否则"禁用"就只是个展示属性
        verify(chunkSearchService).searchManyDetailed(anyLong(),
                argThat(ids -> ids != null && ids.size() == 1 && ids.contains(kbA)), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("零命中：emptyReason = NO_RESULTS（库有、索引没有内容时也是这个结论）")
    void emptyResultReportsNoResults() throws Exception {
        String token = registerAndLogin("search-empty");
        createKb(token, "空库");

        when(chunkSearchService.searchManyDetailed(anyLong(), anyList(), anyString(), any(), any(), any()))
                .thenReturn(new ChunkSearchService.MultiKbSearchResult(List.of(), List.of()));

        mockMvc.perform(post("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"根本不存在的词\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(0))
                .andExpect(jsonPath("$.data.emptyReason").value("NO_RESULTS"));
    }

    @Test
    @DisplayName("一个库都没有：emptyReason = NO_KB，且根本不进检索链路")
    void noKnowledgeBaseReportsNoKb() throws Exception {
        String token = registerAndLogin("search-nokb");

        mockMvc.perform(post("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"redis\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(0))
                .andExpect(jsonPath("$.data.emptyReason").value("NO_KB"));

        verify(chunkSearchService, never()).searchManyDetailed(anyLong(), anyList(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("explain：rank 连续、fusedScore 与两路名次自洽、重排按分数重排")
    void explainIsSelfConsistent() throws Exception {
        String token = registerAndLogin("search-explain");
        long kbId = createKb(token, "Redis 源码");

        // 关键词路：11 / 12 / 13；向量路：12 / 13 / 11 —— 融合后 12 应当第一
        when(explainRecaller.keywordHits(anyLong(), anyString(), anyInt(), any()))
                .thenReturn(List.of(chunkHit(11L, 3.0), chunkHit(12L, 2.0), chunkHit(13L, 1.0)));
        when(explainRecaller.vectorHits(any(), anyString(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(chunkHit(12L, 0.9), chunkHit(13L, 0.8), chunkHit(11L, 0.7)));
        // 重排候选顺序 = 融合顺序 [12, 11, 13]，故意给一个会打乱顺序的分数
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(0.9, 0.1, 0.5));

        String body = mockMvc.perform(post("/api/search/explain")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"redis","kbIds":[%d],"rerank":true}
                                """.formatted(kbId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.bm25.length()").value(3))
                .andExpect(jsonPath("$.data.vector.length()").value(3))
                .andExpect(jsonPath("$.data.bm25[0].chunkId").value("11"))
                .andExpect(jsonPath("$.data.bm25[0].rank").value(1))
                .andExpect(jsonPath("$.data.vector[0].chunkId").value("12"))
                .andExpect(jsonPath("$.data.fused.length()").value(3))
                .andExpect(jsonPath("$.data.fused[0].chunkId").value("12"))
                .andExpect(jsonPath("$.data.fused[0].rank").value(1))
                .andExpect(jsonPath("$.data.fused[0].bm25Rank").value(2))
                .andExpect(jsonPath("$.data.fused[0].vectorRank").value(1))
                .andExpect(jsonPath("$.data.rerank.length()").value(3))
                .andExpect(jsonPath("$.data.rerank[0].chunkId").value("12"))
                .andExpect(jsonPath("$.data.rerank[0].rerankScore").value(0.9))
                .andExpect(jsonPath("$.data.rerank[1].chunkId").value("13"))
                .andExpect(jsonPath("$.data.rerank[2].chunkId").value("11"))
                .andExpect(jsonPath("$.data.degradedKbs.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        // 算术自洽：每条的 fusedScore 必须等于它两路名次算出来的 RRF 分（k=60，与 RrfFusion 同源）
        List<Map<String, Object>> fused = JsonPath.read(body, "$.data.fused");
        assertThat(fused).hasSize(3);
        for (int i = 0; i < fused.size(); i++) {
            Map<String, Object> item = fused.get(i);
            double expected = 0d;
            Object bm25Rank = item.get("bm25Rank");
            Object vectorRank = item.get("vectorRank");
            if (bm25Rank != null) {
                expected += 1.0 / (60 + ((Number) bm25Rank).intValue());
            }
            if (vectorRank != null) {
                expected += 1.0 / (60 + ((Number) vectorRank).intValue());
            }
            double actual = ((Number) item.get("fusedScore")).doubleValue();
            assertThat(actual).as("fusedScore 必须等于 1/(60+bm25Rank)+1/(60+vectorRank)").isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
            // rank 从 1 连续增长，而且分数单调不增——否则"排序"这件事就没发生
            assertThat(item.get("rank")).isEqualTo(i + 1);
            if (i > 0) {
                double previous = ((Number) fused.get(i - 1).get("fusedScore")).doubleValue();
                assertThat(actual).isLessThanOrEqualTo(previous);
            }
        }
    }

    @Test
    @DisplayName("explain：一个库炸了仍给出另一个库的剖析，并把失败的库报出来")
    void explainDegradesWhenOneKnowledgeBaseFails() throws Exception {
        String token = registerAndLogin("search-explain-degrade");
        long kbOk = createKb(token, "正常库");
        long kbBroken = createKb(token, "坏库");

        when(explainRecaller.keywordHits(anyLong(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            Long kbId = invocation.getArgument(0);
            if (kbId != null && kbId == kbBroken) {
                throw new IllegalStateException("milvus down");
            }
            return List.of(chunkHit(41L, 2.0));
        });
        when(explainRecaller.vectorHits(any(), anyString(), anyInt(), anyDouble(), any())).thenAnswer(invocation -> {
            KnowledgeBasePO knowledgeBase = invocation.getArgument(0);
            if (knowledgeBase != null && knowledgeBase.getId() == kbBroken) {
                throw new IllegalStateException("milvus down");
            }
            return List.of(chunkHit(41L, 0.8));
        });

        mockMvc.perform(post("/api/search/explain")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"q":"redis","kbIds":[%d,%d]}
                                """.formatted(kbOk, kbBroken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fused.length()").value(1))
                .andExpect(jsonPath("$.data.fused[0].chunkId").value("41"))
                .andExpect(jsonPath("$.data.degradedKbs.length()").value(1))
                .andExpect(jsonPath("$.data.degradedKbs[0].kbId").value(String.valueOf(kbBroken)))
                .andExpect(jsonPath("$.data.degradedKbs[0].reason").isNotEmpty());
    }

    // ------------------------------------------------------------------ 辅助

    private static ChunkSearchService.SearchHit hit(long chunkId, double score, long knowledgeBaseId, String title) {
        return new ChunkSearchService.SearchHit(chunkId, chunkId + 100, title, "一、概述",
                "正文片段 " + chunkId, score, "hybrid", List.of("BM25 #1"), knowledgeBaseId);
    }

    private static ChunkSearchHit chunkHit(long chunkId, double score) {
        return new ChunkSearchHit(chunkId, score, "bm25");
    }

    private long createKb(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(body, "$.data.id").toString());
    }

    private String registerAndLogin(String username) throws Exception {
        String unique = username + "-" + SEQ.incrementAndGet();
        String payload = """
                {"username":"%s","password":"%s","email":"%s@example.com"}
                """.formatted(unique, PASSWORD, unique);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(unique, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }
}
