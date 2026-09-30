package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过滤条件从门面下推到两路的测试。
 *
 * <p>要证明的三件事（都是"不报错但结果是错的"那类问题）：
 * <ol>
 *   <li>关键词路：条件原样交给 ES（ES 有 tags/folder 字段，索引侧过滤）；</li>
 *   <li>向量路：先解析成文档 id 再下推（pgvector 表里没有标签/目录列）；</li>
 *   <li><b>解析出零篇时两路都不执行</b>，直接空结果——而不是"把条件丢掉继续检索"。</li>
 * </ol>
 */
@DisplayName("检索门面：元数据过滤下推")
class ChunkSearchServiceFilterTest {

    private static final long USER_ID = 1L;
    private static final long KB_ID = 1001L;
    private static final long DOCUMENT_ID = 2001L;
    private static final String QUERY = "缓存击穿";

    private ChunkIndex chunkIndex;
    private VectorStore vectorStore;
    private DocumentTagMapper documentTagMapper;
    private KnowledgeBasePO knowledgeBase;
    private ChunkSearchService search;

    @BeforeEach
    void setUp() {
        chunkIndex = mock(ChunkIndex.class);
        vectorStore = mock(VectorStore.class);
        documentTagMapper = mock(DocumentTagMapper.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeEmbeddingResolver embeddingResolver = mock(KnowledgeEmbeddingResolver.class);

        knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setWorkspaceId(9L);
        knowledgeBase.setEmbeddingProvider("ollama");
        knowledgeBase.setEmbeddingModel("bge-m3");
        knowledgeBase.setEmbeddingDim(1024);
        // 固定成 5 条 / 0.25：下面的深度断言才好读（5 × 3 = 15）
        knowledgeBase.setRetrievalTopK(5);
        knowledgeBase.setSimilarityThreshold(0.25);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[1024]);
        when(embeddingResolver.resolve(any())).thenReturn(embeddingClient);

        // 两路都返回"要多少给多少"，这样"返回几条"只由参数决定，不受假数据条数干扰
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
        when(chunkMapper.selectByIds(anyList())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return ids.stream().map(id -> chunk(((Number) id).longValue())).toList();
        });
        when(documentMapper.selectByIds(anyList())).thenReturn(List.of(document()));

        ChunkHydrator hydrator = new ChunkHydrator(chunkMapper, documentMapper);
        RetrievalFilterResolver filterResolver = new RetrievalFilterResolver(documentTagMapper, documentMapper);
        VectorRetriever vectorRetriever = new VectorRetriever(
                knowledgeBaseService, embeddingResolver, vectorStore, hydrator, filterResolver);
        KeywordRetriever keywordRetriever = new KeywordRetriever(knowledgeBaseService, chunkIndex, hydrator);
        HybridRetriever hybridRetriever = new HybridRetriever(
                knowledgeBaseService, vectorRetriever, keywordRetriever, hydrator, filterResolver);
        RetrieverRegistry registry = new RetrieverRegistry(
                List.of(vectorRetriever, keywordRetriever, hybridRetriever));

        search = new ChunkSearchServiceImpl(knowledgeBaseService, registry,
                new RetrievalQueryFactory(new RetrievalDefaults()));
    }

    @Test
    @DisplayName("关键词路：条件原样交给索引（ES 有 tags/folder 字段，不需要先查关系库）")
    void keywordLegGetsFiltersAsIs() {
        RetrievalFilters filters = new RetrievalFilters(List.of("算法"), "算法", List.of());

        search.search(USER_ID, KB_ID, QUERY, null, SearchMode.BM25, filters);

        verify(chunkIndex).search(eq(KB_ID), eq(QUERY), eq(15), eq(filters));
        verify(documentTagMapper, never()).selectDocumentIdsByTags(anyLong(), anyList());
    }

    @Test
    @DisplayName("向量路：标签先解析成文档 id，再下推到 SQL")
    void vectorLegGetsResolvedDocumentIds() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("算法"))).thenReturn(List.of(7L, 8L));

        search.search(USER_ID, KB_ID, QUERY, null, SearchMode.VECTOR,
                new RetrievalFilters(List.of("算法"), null, null));

        verify(vectorStore).search(eq(KB_ID), any(float[].class), eq(15), eq(0.25),
                eq(List.of(7L, 8L)));
    }

    @Test
    @DisplayName("混合路：两路都带上过滤，且只解析一次（关键词路不需要解析）")
    void hybridLegPassesFiltersToBothLegs() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("算法"))).thenReturn(List.of(7L, 8L));
        RetrievalFilters filters = new RetrievalFilters(List.of("算法"), null, null);

        search.search(USER_ID, KB_ID, QUERY, null, SearchMode.HYBRID, filters);

        verify(chunkIndex).search(eq(KB_ID), eq(QUERY), eq(15), eq(filters));
        verify(vectorStore).search(eq(KB_ID), any(float[].class), eq(15), eq(0.25),
                eq(List.of(7L, 8L)));
        verify(documentTagMapper).selectDocumentIdsByTags(KB_ID, List.of("算法"));
    }

    @Test
    @DisplayName("条件命中零篇：直接返回空，两路都不检索（绝不退化成\"不过滤\"）")
    void nothingMatchedSkipsBothLegs() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("没人用的标签"))).thenReturn(List.of());

        List<ChunkSearchService.SearchHit> hits = search.search(USER_ID, KB_ID, QUERY, null,
                SearchMode.HYBRID, new RetrievalFilters(List.of("没人用的标签"), null, null));

        assertThat(hits).isEmpty();
        verify(chunkIndex, never()).search(anyLong(), anyString(), anyInt(), any(RetrievalFilters.class));
        verify(vectorStore, never()).search(anyLong(), any(float[].class), anyInt(), anyDouble(), anyList());
    }

    @Test
    @DisplayName("向量单路：条件命中零篇同样不检索该库")
    void nothingMatchedSkipsVectorLeg() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("没人用的标签"))).thenReturn(List.of());

        assertThat(search.search(USER_ID, KB_ID, QUERY, null, SearchMode.VECTOR,
                new RetrievalFilters(List.of("没人用的标签"), null, null))).isEmpty();
        verify(vectorStore, never()).search(anyLong(), any(float[].class), anyInt(), anyDouble(), anyList());
    }

    @Test
    @DisplayName("不过滤时行为与改造前一致：向量路带空的范围（= 全库）")
    void noFiltersKeepsOldBehaviour() {
        search.search(USER_ID, KB_ID, QUERY, null, SearchMode.VECTOR);

        verify(vectorStore).search(eq(KB_ID), any(float[].class), eq(15), eq(0.25), eq(List.<Long>of()));
        verify(documentTagMapper, never()).selectDocumentIdsByTags(anyLong(), anyList());
    }

    // ------------------------------------------------------------------

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
