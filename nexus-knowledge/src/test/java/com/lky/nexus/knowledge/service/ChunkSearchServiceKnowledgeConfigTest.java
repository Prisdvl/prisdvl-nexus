package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrieverRegistry;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.retriever.ChunkHydrator;
import com.lky.nexus.knowledge.rag.retriever.HybridRetriever;
import com.lky.nexus.knowledge.rag.retriever.KeywordRetriever;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFilterResolver;
import com.lky.nexus.knowledge.rag.retriever.RetrievalQueryFactory;
import com.lky.nexus.knowledge.rag.retriever.VectorRetriever;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import com.lky.nexus.knowledge.service.impl.ChunkSearchServiceImpl;
import com.lky.nexus.knowledge.vector.VectorSearchHit;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库的检索参数<b>真的生效</b>吗（Phase 3.2）。
 *
 * <p>为什么单独写这一组用例：这两个字段（{@code retrieval_top_k} / {@code similarity_threshold}）
 * 在 Phase 2 就落库并暴露在 API 上了，界面也能改，但<b>检索时从来没被读过</b>——
 * 属于"能配置"却"不生效"。这种假可配置最危险的地方是它不会报错：
 * 用户把条数从 5 改成 20，看到的还是 5 条结果，只会以为"这个功能没用"。
 *
 * <p>断言刻意落在<b>传出去的参数</b>上（召回深度与相似度下限），而不是"有没有调用配置对象"：
 * 条数要乘上召回倍数（{@code 3×}）才落到 ES/向量库，阈值要落成向量检索的 {@code minScore}；
 * 只有这些具体数字能证明参数一路走到了基础设施，而不是在中间某一层被丢掉。
 */
class ChunkSearchServiceKnowledgeConfigTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_ID = 1001L;
    private static final Long DOCUMENT_ID = 900L;

    private static final String QUERY = "问题";

    private ChunkIndex chunkIndex;
    private VectorStore vectorStore;
    private KnowledgeBaseService knowledgeBaseService;
    private KnowledgeEmbeddingResolver embeddingResolver;
    private ChunkMapper chunkMapper;

    /** 可改的全局默认值：用来证明"没配就用全局默认"是真的读它，而不是又一处写死的数字。 */
    private RetrievalDefaults defaults;
    /** 被测知识库的配置，由各用例设置。 */
    private KnowledgeBasePO knowledgeBase;

    private ChunkSearchService search;

    @BeforeEach
    void setUp() {
        defaults = new RetrievalDefaults();
        chunkIndex = mock(ChunkIndex.class);
        vectorStore = mock(VectorStore.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        embeddingResolver = mock(KnowledgeEmbeddingResolver.class);
        chunkMapper = mock(ChunkMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);

        knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setWorkspaceId(9L);
        knowledgeBase.setEmbeddingProvider("ollama");
        knowledgeBase.setEmbeddingModel("bge-m3");
        knowledgeBase.setEmbeddingDim(1024);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[1024]);
        when(embeddingResolver.resolve(any())).thenReturn(embeddingClient);

        stubRecall();
        when(chunkMapper.selectByIds(anyList())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return ids.stream().map(id -> chunk(((Number) id).longValue())).toList();
        });
        when(documentMapper.selectByIds(anyList())).thenReturn(List.of(document()));

        ChunkHydrator hydrator = new ChunkHydrator(chunkMapper, documentMapper);
        // 本用例只关心"参数解析"，检索永远不带过滤条件，解析器因此不会被触发
        RetrievalFilterResolver filterResolver = new RetrievalFilterResolver(
                mock(DocumentTagMapper.class), documentMapper);
        VectorRetriever vectorRetriever = new VectorRetriever(
                knowledgeBaseService, embeddingResolver, vectorStore, hydrator, filterResolver);
        KeywordRetriever keywordRetriever = new KeywordRetriever(knowledgeBaseService, chunkIndex, hydrator);
        HybridRetriever hybridRetriever = new HybridRetriever(
                knowledgeBaseService, vectorRetriever, keywordRetriever, hydrator, filterResolver);
        RetrieverRegistry registry = new RetrieverRegistry(
                List.of(vectorRetriever, keywordRetriever, hybridRetriever));

        search = new ChunkSearchServiceImpl(knowledgeBaseService, registry,
                new RetrievalQueryFactory(defaults),
                mock(KnowledgeEmbeddingResolver.class));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("调用方没指定条数：用知识库的 retrieval_top_k（并把召回深度乘到 3 倍）")
    void knowledgeBaseTopKAppliesWhenCallerDoesNotSpecify() {
        knowledgeBase.setRetrievalTopK(4);
        knowledgeBase.setSimilarityThreshold(null);

        List<ChunkSearchService.SearchHit> hits = search(SearchMode.HYBRID, null);

        // 4 条 × 3 倍深度 = 12，两路都必须按这个深度去召回
        verify(chunkIndex).search(eq(KB_ID), eq(QUERY), eq(12), eq(RetrievalFilters.none()));
        verify(vectorStore).search(eq(KB_ID), any(float[].class), eq(12), eq(0.25),
                eq(List.<Long>of()));
        assertThat(hits).as("返回条数也要按知识库配置收窄，否则等于没生效")
                .hasSize(4);
    }

    @Test
    @DisplayName("知识库的 similarity_threshold 落成向量检索的 minScore")
    void knowledgeBaseThresholdReachesVectorStore() {
        knowledgeBase.setRetrievalTopK(null);
        knowledgeBase.setSimilarityThreshold(0.42);

        search(SearchMode.VECTOR, null);

        verify(vectorStore).search(eq(KB_ID), any(float[].class), anyInt(), eq(0.42),
                eq(List.<Long>of()));
    }

    @Test
    @DisplayName("调用方显式传的条数覆盖知识库配置（优先级：请求 > 知识库 > 全局默认）")
    void explicitRequestWinsOverKnowledgeBaseConfig() {
        knowledgeBase.setRetrievalTopK(4);

        search(SearchMode.BM25, 2);

        verify(chunkIndex).search(eq(KB_ID), eq(QUERY), eq(6), eq(RetrievalFilters.none()));
    }

    @Test
    @DisplayName("知识库没配（null）：用全局默认 nexus.retrieval.*，而不是某处的写死值")
    void globalDefaultAppliesWhenKnowledgeBaseHasNone() {
        defaults.setTopK(7);
        defaults.setSimilarityThreshold(0.31);
        knowledgeBase.setRetrievalTopK(null);
        knowledgeBase.setSimilarityThreshold(null);

        search(SearchMode.VECTOR, null);

        verify(vectorStore).search(eq(KB_ID), any(float[].class), eq(21), eq(0.31),
                eq(List.<Long>of()));
    }

    @Test
    @DisplayName("越界值夹取：知识库配 999 条按上限 100 算；请求传 0 仍按旧的\"至少 1 条\"算")
    void outOfRangeValuesAreClamped() {
        knowledgeBase.setRetrievalTopK(999);
        search(SearchMode.BM25, null);
        // 条数上限 100 → 深度本该 300，再被单路候选上限 200 夹住
        verify(chunkIndex).search(eq(KB_ID), eq(QUERY), eq(RetrievalQuery.MAX_CANDIDATES),
                eq(RetrievalFilters.none()));

        knowledgeBase.setRetrievalTopK(null);
        search(SearchMode.BM25, 0);
        verify(chunkIndex).search(eq(KB_ID), eq(QUERY), eq(3), eq(RetrievalFilters.none()));
    }

    @Test
    @DisplayName("阈值配成 0（非正数）视为\"没配\"，退回全局默认——0 在本项目里不是\"不过滤\"")
    void nonPositiveThresholdFallsBackToGlobalDefault() {
        knowledgeBase.setSimilarityThreshold(0.0);

        search(SearchMode.VECTOR, null);

        verify(vectorStore).search(eq(KB_ID), any(float[].class), anyInt(), eq(0.25),
                eq(List.<Long>of()));
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private List<ChunkSearchService.SearchHit> search(SearchMode mode, Integer topK) {
        return search.search(USER_ID, KB_ID, QUERY, topK, mode);
    }

    /**
     * 两路都返回"要多少给多少"的命中，且分数与名次一致。
     *
     * <p>这样条数断言才有意义：如果假索引只肯给 3 条，返回 3 条就说不清是"配置生效"还是"索引只有 3 条"。
     */
    private void stubRecall() {
        when(chunkIndex.search(eq(KB_ID), anyString(), anyInt(), any(RetrievalFilters.class)))
                .thenAnswer(invocation -> {
            int depth = invocation.getArgument(2);
            List<ChunkSearchHit> hits = new ArrayList<>(depth);
            for (int i = 1; i <= depth; i++) {
                hits.add(new ChunkSearchHit((long) i, 10.0 - i, "bm25"));
            }
            return hits;
        });
        when(vectorStore.search(eq(KB_ID), any(float[].class), anyInt(), anyDouble(), anyList()))
                .thenAnswer(invocation -> {
                    int depth = invocation.getArgument(2);
                    List<VectorSearchHit> hits = new ArrayList<>(depth);
                    for (int i = 1; i <= depth; i++) {
                        hits.add(new VectorSearchHit((long) i, 1.0 - i * 0.001));
                    }
                    return hits;
                });
    }

    /** 正文里的编号后面带句号：否则"正文 1"是"正文 12"的子串，会被上下文去重当成重叠片段丢掉。 */
    private static ChunkPO chunk(Long id) {
        ChunkPO chunk = new ChunkPO();
        chunk.setId(id);
        chunk.setKbId(KB_ID);
        chunk.setDocumentId(DOCUMENT_ID);
        chunk.setContent("这是一段独一无二的资料内容，编号 " + id + "。");
        return chunk;
    }

    private static DocumentPO document() {
        DocumentPO document = new DocumentPO();
        document.setId(DOCUMENT_ID);
        document.setKbId(KB_ID);
        document.setTitle("缓存笔记");
        document.setFileName("cache.md");
        return document;
    }
}
