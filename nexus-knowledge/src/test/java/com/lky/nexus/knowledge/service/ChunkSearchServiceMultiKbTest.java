package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrieverRegistry;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
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
import com.lky.nexus.knowledge.service.impl.ChunkSearchServiceImpl;
import com.lky.nexus.knowledge.vector.VectorSearchHit;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多知识库检索（Phase 5.5）。
 *
 * <p>这一组里最重要的是第一条：<b>每个库按自己的 {@code retrieval_top_k} 召回</b>。
 * 它极易被"顺手"写错，而且错了之后功能看着完全正常——
 * {@code RetrievalQueryFactory.resolveTopK} 的优先级是"调用方 &gt; 知识库配置"，
 * 所以只要把调用方的 {@code topK} 传进逐库构造，所有库就都会用同一个召回深度，
 * {@code retrieval_top_k} 整列静默失效，而这正是 Phase 3.2 全部工作的落点。
 * 断言落在"传给 ES 的召回深度"这个具体数字上，而不是"有没有读配置"——
 * 只有数字能证明参数一路走到了基础设施。
 *
 * <p>其余几条守住多库新增的语义：归并后按<b>全局</b> {@code topK} 截断（不随库数放大）、
 * 每条命中带上<b>自己的</b>库 id、重复绑定的库只检索一次、
 * 以及嵌入模型不一致时提前失败并指出是哪个库。
 */
class ChunkSearchServiceMultiKbTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_A = 1001L;
    private static final Long KB_B = 1002L;
    private static final Long DOC_ID = 900L;

    private static final String QUERY = "问题";

    private ChunkIndex chunkIndex;
    private VectorStore vectorStore;
    private KnowledgeBaseService knowledgeBaseService;
    private KnowledgeEmbeddingResolver embeddingResolver;
    private ChunkMapper chunkMapper;
    private RetrievalDefaults defaults;

    private KnowledgeBasePO kbA;
    private KnowledgeBasePO kbB;

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

        kbA = knowledgeBase(KB_A, 9L);
        kbB = knowledgeBase(KB_B, 9L);

        // 让这个桩的行为与真实实现一致：按请求顺序去重后返回实体。
        // 这样"重复绑定只算一次"验的是门面与校验层的协作，而不是把去重偷偷塞进桩里
        when(knowledgeBaseService.requireAllOwned(eq(USER_ID), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            Map<Long, KnowledgeBasePO> byId = Map.of(KB_A, kbA, KB_B, kbB);
            return ids.stream().distinct().map(byId::get).filter(Objects::nonNull).toList();
        });
        when(knowledgeBaseService.requireOwned(USER_ID, KB_A)).thenReturn(kbA);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_B)).thenReturn(kbB);

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[1024]);
        when(embeddingResolver.resolve(any())).thenReturn(embeddingClient);

        // 两个库的命中 id 段与分数段都不同：id 段不同才看得出结果来自两个库，
        // 分数交错（10.x vs 9.x）才证明真的做了跨库归并，而不是"把 A 的全部排在 B 前面"
        stubKeywordRecall(KB_A, 1L, 10.0);
        stubKeywordRecall(KB_B, 101L, 9.5);
        stubVectorRecall(KB_A, 201L, 0.9);
        stubVectorRecall(KB_B, 301L, 0.8);

        when(chunkMapper.selectByIds(anyList())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return ids.stream().map(id -> chunk(((Number) id).longValue())).toList();
        });
        when(documentMapper.selectByIds(anyList())).thenReturn(List.of(document()));

        ChunkHydrator hydrator = new ChunkHydrator(chunkMapper, documentMapper);
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
                new RetrievalQueryFactory(defaults), embeddingResolver);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("每个库按自己的 retrieval_top_k 召回，而不是共用调用方传的 topK")
    void eachKnowledgeBaseRecallsAtItsOwnTopK() {
        kbA.setRetrievalTopK(2);
        kbB.setRetrievalTopK(6);

        search.searchMany(USER_ID, List.of(KB_A, KB_B), QUERY, 5, SearchMode.BM25);

        // 深度 = 各自的 retrieval_top_k × 3。若实现把调用方的 5 传进逐库构造，
        // 这两个数字都会变成 15，而 retrieval_top_k 就再也不生效了
        verify(chunkIndex).search(eq(KB_A), eq(QUERY), eq(6), eq(RetrievalFilters.none()));
        verify(chunkIndex).search(eq(KB_B), eq(QUERY), eq(18), eq(RetrievalFilters.none()));
    }

    @Test
    @DisplayName("归并后按全局 topK 截断：多绑库不会让上下文变长")
    void mergeIsTruncatedByGlobalTopK() {
        kbA.setRetrievalTopK(20);
        kbB.setRetrievalTopK(20);

        List<ChunkSearchService.SearchHit> hits =
                search.searchMany(USER_ID, List.of(KB_A, KB_B), QUERY, 3, SearchMode.BM25);

        assertThat(hits).as("两个库各召回 20 条，但最终只留 3 条").hasSize(3);
    }

    @Test
    @DisplayName("topK 传 null：归并上限用全局默认 nexus.retrieval.top-k")
    void nullTopKUsesGlobalDefaultForMergeLimit() {
        defaults.setTopK(4);
        kbA.setRetrievalTopK(20);
        kbB.setRetrievalTopK(20);

        List<ChunkSearchService.SearchHit> hits =
                search.searchMany(USER_ID, List.of(KB_A, KB_B), QUERY, null, SearchMode.BM25);

        assertThat(hits).hasSize(4);
    }

    @Test
    @DisplayName("跨库归并：按分数降序交错，且每条命中带自己的库 id")
    void mergesAcrossKnowledgeBasesAndKeepsOrigin() {
        kbA.setRetrievalTopK(3);
        kbB.setRetrievalTopK(3);

        List<ChunkSearchService.SearchHit> hits =
                search.searchMany(USER_ID, List.of(KB_A, KB_B), QUERY, 10, SearchMode.BM25);

        // 分数交错：A 的 9.x 与 B 的 8.x 混排，说明真的按分数归并了
        assertThat(hits).hasSize(6)
                .extracting(ChunkSearchService.SearchHit::knowledgeBaseId)
                .contains(KB_A, KB_B);
        assertThat(hits).isSortedAccordingTo(
                Comparator.comparingDouble(ChunkSearchService.SearchHit::score).reversed());
        // 出处必须能落到具体某一条上：前两条分别来自两个库
        assertThat(hits.get(0).knowledgeBaseId()).isEqualTo(KB_A);
        assertThat(hits.get(1).knowledgeBaseId()).isEqualTo(KB_B);
    }

    @Test
    @DisplayName("只绑一个库：结果与单库检索逐字段一致（含每条命中的库 id）")
    void singleKnowledgeBaseMatchesSingleSearch() {
        kbA.setRetrievalTopK(3);

        List<ChunkSearchService.SearchHit> many =
                search.searchMany(USER_ID, List.of(KB_A), QUERY, null, SearchMode.BM25);
        List<ChunkSearchService.SearchHit> single =
                search.search(USER_ID, KB_A, QUERY, null, SearchMode.BM25);

        assertThat(many).isEqualTo(single);
        assertThat(many).allSatisfy(
                hit -> assertThat(hit.knowledgeBaseId()).isEqualTo(KB_A));
    }

    @Test
    @DisplayName("同一个库绑两次：只检索一次（去重发生在门面之前）")
    void duplicatedKnowledgeBaseIsRecalledOnce() {
        kbA.setRetrievalTopK(2);

        search.searchMany(USER_ID, List.of(KB_A, KB_A), QUERY, 5, SearchMode.BM25);

        verify(chunkIndex, times(1)).search(eq(KB_A), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("某库的冻结嵌入模型与全局配置不一致 → 4006，且指名是哪个库（不是模糊的\"两个模型名\"）")
    void embeddingMismatchNamesTheOffendingKnowledgeBase() {
        kbB.setName("多库B");
        when(embeddingResolver.resolve(kbB)).thenThrow(new BusinessException(
                ErrorCode.MODEL_ROLE_NOT_CONFIGURED, "知识库冻结的嵌入模型是 x:y，而当前配置指向 a:b"));

        assertThatThrownBy(() ->
                search.searchMany(USER_ID, List.of(KB_A, KB_B), QUERY, 5, SearchMode.HYBRID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode()))
                .hasMessageContaining("多库B")
                .hasMessageContaining(String.valueOf(KB_B));
        // 预检在召回之前：一个库不合格就不该让别的库白跑一轮检索
        verify(chunkIndex, never()).search(any(), anyString(), anyInt(), any());
        verify(vectorStore, never()).search(any(), any(float[].class), anyInt(), any(double.class), anyList());
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private static KnowledgeBasePO knowledgeBase(Long id, Long workspaceId) {
        KnowledgeBasePO entity = new KnowledgeBasePO();
        entity.setId(id);
        entity.setUserId(USER_ID);
        entity.setWorkspaceId(workspaceId);
        entity.setName("库" + id);
        entity.setEmbeddingProvider("ollama");
        entity.setEmbeddingModel("bge-m3");
        entity.setEmbeddingDim(1024);
        return entity;
    }

    /** 关键词路：要多少给多少，分数从 topScore 递减。 */
    private void stubKeywordRecall(Long kbId, long idBase, double topScore) {
        when(chunkIndex.search(eq(kbId), anyString(), anyInt(), any(RetrievalFilters.class)))
                .thenAnswer(invocation -> {
                    int depth = invocation.getArgument(2);
                    List<ChunkSearchHit> hits = new ArrayList<>(depth);
                    for (int i = 0; i < depth; i++) {
                        hits.add(new ChunkSearchHit(idBase + i, topScore - i, "bm25"));
                    }
                    return hits;
                });
    }

    private void stubVectorRecall(Long kbId, long idBase, double topScore) {
        when(vectorStore.search(eq(kbId), any(float[].class), anyInt(), any(double.class), anyList()))
                .thenAnswer(invocation -> {
                    int depth = invocation.getArgument(2);
                    List<VectorSearchHit> hits = new ArrayList<>(depth);
                    for (int i = 0; i < depth; i++) {
                        hits.add(new VectorSearchHit(idBase + i, topScore - i * 0.01));
                    }
                    return hits;
                });
    }

    /** 切片的库由 id 段决定（1xx 属于 A，2xx 属于 B...），便于一眼看出命中的出处。 */
    private static ChunkPO chunk(Long id) {
        ChunkPO chunk = new ChunkPO();
        chunk.setId(id);
        chunk.setKbId(id < 200 ? KB_A : KB_B);
        chunk.setDocumentId(DOC_ID);
        chunk.setContent("这是一段独一无二的资料内容，编号 " + id + "。");
        return chunk;
    }

    private static DocumentPO document() {
        DocumentPO document = new DocumentPO();
        document.setId(DOC_ID);
        document.setKbId(KB_A);
        document.setTitle("缓存笔记");
        document.setFileName("cache.md");
        return document;
    }
}
