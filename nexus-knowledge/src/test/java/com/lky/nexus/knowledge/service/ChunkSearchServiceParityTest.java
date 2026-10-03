package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrieverRegistry;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.rag.Reranker;
import com.lky.nexus.knowledge.rag.retriever.ChunkHydrator;
import com.lky.nexus.knowledge.rag.retriever.HybridRetriever;
import com.lky.nexus.knowledge.rag.retriever.KeywordRetriever;
import com.lky.nexus.knowledge.rag.retriever.MultiKnowledgeRetriever;
import com.lky.nexus.knowledge.rag.retriever.RerankedRetriever;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFilterResolver;
import com.lky.nexus.knowledge.rag.retriever.RetrievalQueryFactory;
import com.lky.nexus.knowledge.rag.retriever.VectorRetriever;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.IndexedChunk;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import com.lky.nexus.knowledge.service.impl.ChunkSearchServiceLegacyReference;
import com.lky.nexus.knowledge.service.impl.ChunkSearchServiceImpl;
import com.lky.nexus.knowledge.vector.VectorRecord;
import com.lky.nexus.knowledge.vector.VectorSearchHit;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索重构的<b>行为不变证明</b>（Phase 3.1：把检索逻辑拆成 Retriever 家族）。
 *
 * <p>为什么必须要有这个测试：端口抽象、策略拆分、实现搬迁这类重构，
 * 失败方式极其隐蔽——它不会报错，只会让结果"有点不一样"：
 * 顺序变了、重复命中少算一个名次、孤儿命中从跳过变成占位、
 * 分数从 BM25 分变成 RRF 分。这些在代码 review 里几乎看不出来，
 * 却能实打实地改变用户看到的东西（引用对不对得上、为什么这条排第一）。
 *
 * <p>做法是把改造前的实现逐字固化在测试代码里
 * （{@link ChunkSearchServiceLegacyReference}），让<b>新旧两个实现在同一份
 * 假数据上跑同一组场景，逐字段比对结果</b>。断言分两层：
 * <ol>
 *   <li><b>先断言具体期望值</b>：否则"两边都返回空"也能让比对通过，
 *       测试就成了摆设；</li>
 *   <li><b>再断言新旧完全一致</b>：这一层保证以后有人改新实现时，
 *       任何偏离旧行为的改动都会立刻失败。</li>
 * </ol>
 *
 * <p>这里刻意用假的索引/向量库（而不是真 ES + pgvector）：真基础设施在
 * 本机 Docker/WSL2 不可用时跑不起来，而"融合顺序、名次语义、回表截断"
 * 这些恰好都是纯逻辑，不需要真机也能证明。
 */
class ChunkSearchServiceParityTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_ID = 1001L;

    private FakeChunkIndex chunkIndex;
    private FakeVectorStore vectorStore;
    private KnowledgeBaseService knowledgeBaseService;
    private KnowledgeEmbeddingResolver embeddingResolver;
    private ChunkMapper chunkMapper;
    private DocumentMapper documentMapper;
    private RetrieverRegistry registry;

    /**
     * 改造前的实现（行为基准）。
     *
     * <p>刻意<b>按具体类型声明</b>而不是 {@code ChunkSearchService}：参照实现不再实现那个接口，
     * 因此以后接口签名再变（3.3 的过滤条件、3.5 的 workspace 参数），这里都会编译不过，
     * 逼人显式决定"新行为是不是真的等价"，而不是让两边一起被接口拖着走、悄悄失去把关作用。
     */
    private ChunkSearchServiceLegacyReference legacyReference;
    /** 拆成 Retriever 家族之后的实现。 */
    private ChunkSearchServiceImpl current;

    @BeforeEach
    void setUp() {
        chunkIndex = new FakeChunkIndex();
        vectorStore = new FakeVectorStore();
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        embeddingResolver = mock(KnowledgeEmbeddingResolver.class);
        chunkMapper = mock(ChunkMapper.class);
        documentMapper = mock(DocumentMapper.class);

        KnowledgeBasePO knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setWorkspaceId(9L);
        knowledgeBase.setEmbeddingProvider("ollama");
        knowledgeBase.setEmbeddingModel("bge-m3");
        knowledgeBase.setEmbeddingDim(1024);
        // 检索参数**不设置**（null）：此时解析结果必须落到全局默认（topK 5 / 阈值 0.25），
        // 也就是改造前写死的那个 0.25 —— 本用例"新旧逐字段一致"的前提正是这一点。
        // "知识库配置真的生效了"由 ChunkSearchServiceKnowledgeConfigTest 单独证明。
        knowledgeBase.setRetrievalTopK(null);
        knowledgeBase.setSimilarityThreshold(null);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);
        // 越权/不存在的知识库：两个实现都必须原样抛出 3001
        when(knowledgeBaseService.requireOwned(USER_ID, 999L)).thenThrow(
                new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[1024]);
        when(embeddingResolver.resolve(any())).thenReturn(embeddingClient);

        legacyReference = new ChunkSearchServiceLegacyReference(
                knowledgeBaseService, embeddingResolver, chunkIndex, vectorStore, chunkMapper, documentMapper);

        ChunkHydrator hydrator = new ChunkHydrator(chunkMapper, documentMapper);
        // 本用例只跑"不带过滤"的检索（新旧一致的前提），所以过滤器解析器永远不会被调用；
        // 它的两个 mapper 用 mock 即可。"过滤真的下推了"由 ChunkSearchServiceFilterTest 证明。
        RetrievalFilterResolver filterResolver = new RetrievalFilterResolver(
                mock(DocumentTagMapper.class), documentMapper);
        VectorRetriever vectorRetriever = new VectorRetriever(
                knowledgeBaseService, embeddingResolver, vectorStore, hydrator, filterResolver);
        KeywordRetriever keywordRetriever = new KeywordRetriever(
                knowledgeBaseService, chunkIndex, hydrator);
        HybridRetriever hybridRetriever = new HybridRetriever(
                knowledgeBaseService, vectorRetriever, keywordRetriever, hydrator, filterResolver);
        RerankedRetriever rerankedRetriever = new RerankedRetriever(hybridRetriever, mock(Reranker.class));
        registry = new RetrieverRegistry(
                List.of(vectorRetriever, keywordRetriever, hybridRetriever, rerankedRetriever));
        current = new ChunkSearchServiceImpl(knowledgeBaseService, registry,
                new RetrievalQueryFactory(new RetrievalDefaults()),
                // 预检只在 searchMany 里跑，单库路径不碰它——传桩即可
                mock(KnowledgeEmbeddingResolver.class),
                // 单库不切线程：参照实现与新实现在这一点上必须一致（这正是 ParityTest 的职责）
                new MultiKnowledgeRetriever(new RetrievalDefaults(), new ObservabilityRegistry()));
    }

    // ------------------------------------------------------------------
    // 场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BM25 单路：原文优先、失联切片跳过、名次按原始排名（不因跳过而重排）")
    void bm25Mode() {
        chunkIndex.hits(KB_ID,
                hit(11L, 3.2), hit(12L, 2.9), hit(13L, 2.1), hit(14L, 1.4));
        stubChunks(List.of(
                chunk(11L, 201L, "缓存 › 击穿", "带标题路径的正文", null),
                chunk(12L, 201L, null, "参与嵌入的正文", "原文正文"),
                // 13 故意不在库里：索引里还留着已删除的切片（MySQL 与 ES 之间没有分布式事务）
                chunk(14L, 202L, null, "另一篇的正文", null)));
        stubDocuments(List.of(document(201L, "缓存笔记", "cache.md"), document(202L, "另一篇", "other.md")));

        List<ChunkSearchService.SearchHit> hits = run(SearchMode.BM25, "缓存击穿", 5);

        assertThat(hits).hasSize(3);
        assertThat(hits).extracting(ChunkSearchService.SearchHit::chunkId)
                .containsExactly(11L, 12L, 14L);

        ChunkSearchService.SearchHit first = hits.get(0);
        assertThat(first.documentId()).isEqualTo(201L);
        assertThat(first.documentTitle()).isEqualTo("缓存笔记");
        assertThat(first.headingPath()).isEqualTo("缓存 › 击穿");
        assertThat(first.content()).isEqualTo("带标题路径的正文");
        assertThat(first.score()).isEqualTo(3.2);
        assertThat(first.source()).isEqualTo("bm25");
        assertThat(first.matchedBy()).containsExactly("BM25 #1");

        // rawContent 存在时优先用它：参与嵌入的那份带标题路径，不适合展示与引用
        assertThat(hits.get(1).content()).isEqualTo("原文正文");
        assertThat(hits.get(1).matchedBy()).containsExactly("BM25 #2");
        // 关键的隐蔽语义：14 号在原排名里是第 4 位，即便 13 号被跳过，它仍然是 "#4"
        assertThat(hits.get(2).matchedBy()).containsExactly("BM25 #4");
        assertThat(hits.get(2).documentTitle()).isEqualTo("另一篇");

        // 召回深度 = min(200, limit × 3)
        assertThat(chunkIndex.lastKbId).isEqualTo(KB_ID);
        assertThat(chunkIndex.lastQuery).isEqualTo("缓存击穿");
        assertThat(chunkIndex.lastTopK).isEqualTo(15);
        // BM25 单路不该碰向量库与嵌入模型（多打一次嵌入调用 = 白花的钱与延迟）
        assertThat(vectorStore.searchCalls).isZero();
        verify(embeddingResolver, never()).resolve(any());
    }

    @Test
    @DisplayName("向量单路：只用向量库，分数与来源标注取自向量那一路")
    void vectorMode() {
        vectorStore.hits(KB_ID, new VectorSearchHit(21L, 0.81), new VectorSearchHit(22L, 0.55));
        stubChunks(List.of(
                chunk(21L, 201L, "第一篇", "正文 21", null),
                chunk(22L, 201L, "第二篇", "正文 22", null)));
        stubDocuments(List.of(document(201L, "缓存笔记", "cache.md")));

        List<ChunkSearchService.SearchHit> hits = run(SearchMode.VECTOR, "怎么解决", 3);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunkId()).isEqualTo(21L);
        assertThat(hits.get(0).score()).isEqualTo(0.81);
        assertThat(hits.get(0).source()).isEqualTo("vector");
        assertThat(hits.get(0).matchedBy()).containsExactly("向量 #1");
        assertThat(hits.get(1).matchedBy()).containsExactly("向量 #2");

        assertThat(vectorStore.lastTopK).isEqualTo(9);
        assertThat(vectorStore.lastMinScore).isEqualTo(0.25);
        verify(embeddingResolver, atLeastOnce()).resolve(any());
        assertThat(chunkIndex.searchCalls).as("向量单路不该去查关键词索引").isZero();
    }

    @Test
    @DisplayName("混合：RRF 只用排名，两路名次都保留在命中说明里")
    void hybridMode() {
        chunkIndex.hits(KB_ID, hit(31L, 9.9), hit(32L, 8.8), hit(33L, 1.0));
        vectorStore.hits(KB_ID, new VectorSearchHit(32L, 0.7), new VectorSearchHit(31L, 0.6),
                new VectorSearchHit(34L, 0.5));
        stubChunks(List.of(
                chunk(31L, 201L, null, "正文 31", null),
                chunk(32L, 201L, null, "正文 32", null),
                chunk(33L, 202L, null, "正文 33", null),
                chunk(34L, 202L, null, "正文 34", null)));
        stubDocuments(List.of(document(201L, "缓存笔记", "cache.md"), document(202L, "另一篇", "other.md")));

        List<ChunkSearchService.SearchHit> hits = run(SearchMode.HYBRID, "缓存击穿", 5);

        // 31 与 32 的 RRF 分数完全相同（各有一路第 1、一路第 2），因此必须靠
        // "分数相同时按 chunkId 稳定排序" 来决定顺序，否则同一查询两次可能给出不同排序
        assertThat(hits).extracting(ChunkSearchService.SearchHit::chunkId)
                .containsExactly(31L, 32L, 33L, 34L);
        double expectedTop = 1.0 / 61 + 1.0 / 62;
        assertThat(hits.get(0).score()).isCloseTo(expectedTop, within(1e-12));
        assertThat(hits.get(1).score()).isCloseTo(expectedTop, within(1e-12));
        assertThat(hits.get(2).score()).isCloseTo(1.0 / 63, within(1e-12));
        assertThat(hits.get(3).score()).isCloseTo(1.0 / 63, within(1e-12));

        assertThat(hits.get(0).source()).isEqualTo("hybrid");
        assertThat(hits.get(0).matchedBy()).containsExactly("BM25 #1", "向量 #2");
        assertThat(hits.get(1).matchedBy()).containsExactly("BM25 #2", "向量 #1");
        assertThat(hits.get(2).matchedBy()).containsExactly("BM25 #3");
        assertThat(hits.get(3).matchedBy()).containsExactly("向量 #3");
    }

    @Test
    @DisplayName("空白查询：直接返回空，但归属校验仍然执行")
    void blankQuery() {
        List<ChunkSearchService.SearchHit> hits = run(SearchMode.HYBRID, "   ", 5);

        assertThat(hits).isEmpty();
        verify(knowledgeBaseService, atLeastOnce()).requireOwned(USER_ID, KB_ID);
        assertThat(chunkIndex.searchCalls).isZero();
        assertThat(vectorStore.searchCalls).isZero();
    }

    @Test
    @DisplayName("越权知识库 + 空白查询：仍然报 3001，不能变成\"空结果\"")
    void unownedKnowledgeBaseEvenForBlankQuery() {
        assertThatThrownBy(() -> current.search(USER_ID, 999L, "", 5, SearchMode.HYBRID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
        assertThatThrownBy(() -> legacyReference.search(USER_ID, 999L, "", 5, SearchMode.HYBRID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("topK 夹取：[1,100]，召回深度随之被夹到上限 200")
    void topKIsClamped() {
        chunkIndex.hits(KB_ID, hit(51L, 5.0), hit(52L, 4.0), hit(53L, 3.0));
        stubChunks(List.of(chunk(51L, 201L, null, "正文 51", null)));
        stubDocuments(List.of(document(201L, "缓存笔记", "cache.md")));

        assertThat(run(SearchMode.BM25, "q", 0)).hasSize(1);
        assertThat(chunkIndex.lastTopK).as("limit=1 → 深度 3").isEqualTo(3);

        run(SearchMode.BM25, "q", 1000);
        assertThat(chunkIndex.lastTopK).as("limit=100 → 深度 300，被上限夹到 200").isEqualTo(200);
    }

    @Test
    @DisplayName("重复命中：只留第一次出现的那条（分数也取它），但仍占一个名次")
    void duplicateHits() {
        chunkIndex.hits(KB_ID, hit(41L, 5.0), hit(41L, 4.0), hit(42L, 3.0));
        stubChunks(List.of(
                chunk(41L, 201L, null, "正文 41", null),
                chunk(42L, 201L, null, "正文 42", null)));
        stubDocuments(List.of(document(201L, "缓存笔记", "cache.md")));

        List<ChunkSearchService.SearchHit> hits = run(SearchMode.BM25, "q", 3);

        assertThat(hits).extracting(ChunkSearchService.SearchHit::chunkId).containsExactly(41L, 42L);
        assertThat(hits.get(0).score()).isEqualTo(5.0);
        assertThat(hits.get(1).matchedBy()).containsExactly("BM25 #3");
    }

    @Test
    @DisplayName("limit 在回表之前生效：只查需要的切片，不做无用的批量回表")
    void limitAppliesBeforeHydration() {
        chunkIndex.hits(KB_ID, hit(51L, 5.0), hit(52L, 4.0), hit(53L, 3.0), hit(54L, 2.0), hit(55L, 1.0));
        stubChunks(List.of(
                chunk(51L, 201L, null, "正文 51", null),
                chunk(52L, 201L, null, "正文 52", null),
                chunk(53L, 201L, null, "正文 53", null),
                chunk(54L, 201L, null, "正文 54", null),
                chunk(55L, 201L, null, "正文 55", null)));
        stubDocuments(List.of(document(201L, "缓存笔记", "cache.md")));

        List<ChunkSearchService.SearchHit> hits = run(SearchMode.BM25, "q", 2);

        assertThat(hits).extracting(ChunkSearchService.SearchHit::chunkId).containsExactly(51L, 52L);
        assertThat(requestedChunkIds).as("每次回表都只应该查这 2 个 id").isNotEmpty()
                .allSatisfy(ids -> assertThat(ids).containsExactly(51L, 52L));
    }

    @Test
    @DisplayName("两路都召回为空：返回空列表而不是抛错")
    void bothRecallEmpty() {
        assertThat(run(SearchMode.HYBRID, "不存在的词", 5)).isEmpty();
        assertThat(run(SearchMode.BM25, "不存在的词", 5)).isEmpty();
    }

    @Test
    @DisplayName("命中的切片全部已删除：返回空列表（跳过失联命中，不让整次检索失败）")
    void allOrphanHits() {
        chunkIndex.hits(KB_ID, hit(61L, 5.0), hit(62L, 4.0));
        stubChunks(List.of());
        stubDocuments(List.of());

        assertThat(run(SearchMode.BM25, "q", 5)).isEmpty();
    }

    @Test
    @DisplayName("检索模式到内部策略的派发：三个用户可见模式各自落到对应实现")
    void modeDispatch() {
        assertThat(registry.get(RetrievalStrategy.KEYWORD).name()).isEqualTo("keyword");
        assertThat(registry.get(RetrievalStrategy.VECTOR).name()).isEqualTo("vector");
        assertThat(registry.get(RetrievalStrategy.HYBRID).name()).isEqualTo("hybrid");
        assertThat(registry.get(RetrievalStrategy.HYBRID_RERANK).name()).isEqualTo("hybrid-rerank");
        assertThat(registry.supported()).containsExactlyInAnyOrder(RetrievalStrategy.values());
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /**
     * 跑同一个场景两次（新实现 + 参照实现），先比对两者完全一致，再返回新实现的结果。
     *
     * <p>断言顺序刻意如此：如果只比对"两者相等"，两个都错成一样也会通过；
     * 所以每个用例都另有具体期望值断言，这里负责守住"不许偏离旧行为"。
     *
     * <p><b>为什么忽略 {@code knowledgeBaseId}</b>：参照实现是单库的，它产出的
     * {@code SearchHit} 走兼容构造器、该字段恒为 null（见 {@code SearchHit} 上的说明）；
     * 新实现走 9 参构造器，该字段是真实的库 id。这个字段<b>没有"改造前"的取值可比</b>，
     * 硬要给参照实现也填一个，就等于动了那个冻结文件。除此之外<b>每个字段仍逐字段严格比对</b>
     * ——顺序、分数、来源、命中说明都一样容忍不了偏差，护栏没有变松。
     */
    private List<ChunkSearchService.SearchHit> run(SearchMode mode, String query, int topK) {
        List<ChunkSearchService.SearchHit> actual = current.search(USER_ID, KB_ID, query, topK, mode);
        List<ChunkSearchService.SearchHit> baseline = legacyReference.search(USER_ID, KB_ID, query, topK, mode);
        assertThat(actual)
                .as("新实现（Retriever 家族）必须与改造前逐字段一致，包括顺序")
                .usingRecursiveComparison()
                .ignoringFields("knowledgeBaseId")
                .isEqualTo(baseline);
        return actual;
    }

    private static ChunkSearchHit hit(Long chunkId, double score) {
        return new ChunkSearchHit(chunkId, score, "bm25");
    }

    private static ChunkPO chunk(Long id, Long documentId, String headingPath, String content, String rawContent) {
        ChunkPO chunk = new ChunkPO();
        chunk.setId(id);
        chunk.setKbId(KB_ID);
        chunk.setDocumentId(documentId);
        chunk.setHeadingPath(headingPath);
        chunk.setContent(content);
        chunk.setRawContent(rawContent);
        return chunk;
    }

    private static DocumentPO document(Long id, String title, String fileName) {
        DocumentPO document = new DocumentPO();
        document.setId(id);
        document.setKbId(KB_ID);
        document.setTitle(title);
        document.setFileName(fileName);
        return document;
    }

    /** 回表时实际被请求的切片 id 序列（用于证明 limit 在回表前生效）。 */
    private final List<List<Long>> requestedChunkIds = new ArrayList<>();

    private void stubChunks(List<ChunkPO> chunks) {
        when(chunkMapper.selectByIds(anyList())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            requestedChunkIds.add(ids.stream().map(id -> ((Number) id).longValue()).toList());
            return chunks.stream().filter(chunk -> ids.contains(chunk.getId())).toList();
        });
    }

    private void stubDocuments(List<DocumentPO> documents) {
        when(documentMapper.selectByIds(anyList())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return documents.stream().filter(document -> ids.contains(document.getId())).toList();
        });
    }

    // ------------------------------------------------------------------

    /** 假的 BM25 索引：只回放预置结果，并记录最后一次请求参数。 */
    private static final class FakeChunkIndex implements ChunkIndex {

        private final java.util.Map<Long, List<ChunkSearchHit>> byKnowledgeBase = new java.util.HashMap<>();
        private Long lastKbId;
        private String lastQuery;
        private int lastTopK;
        private RetrievalFilters lastFilters;
        private int searchCalls;

        void hits(Long knowledgeBaseId, ChunkSearchHit... hits) {
            byKnowledgeBase.put(knowledgeBaseId, List.of(hits));
        }

        @Override
        public List<ChunkSearchHit> search(Long knowledgeBaseId, String query, int topK,
                                           RetrievalFilters filters) {
            lastKbId = knowledgeBaseId;
            lastQuery = query;
            lastTopK = topK;
            lastFilters = filters;
            searchCalls++;
            return byKnowledgeBase.getOrDefault(knowledgeBaseId, List.of());
        }

        @Override
        public void ensureIndex(Long knowledgeBaseId) {
        }

        @Override
        public void recreateIndex(Long knowledgeBaseId) {
        }

        @Override
        public void indexChunks(List<IndexedChunk> chunks) {
            throw new UnsupportedOperationException("本测试不涉及写入");
        }

        @Override
        public void deleteByDocumentIds(List<Long> documentIds) {
            throw new UnsupportedOperationException("本测试不涉及写入");
        }

        @Override
        public void deleteByKnowledgeBase(Long knowledgeBaseId) {
            throw new UnsupportedOperationException("本测试不涉及写入");
        }

        @Override
        public long countByKnowledgeBase(Long knowledgeBaseId) {
            return byKnowledgeBase.getOrDefault(knowledgeBaseId, List.of()).size();
        }
    }

    /** 假的向量库：只回放预置结果，并记录最后一次请求参数。 */
    private static final class FakeVectorStore implements VectorStore {

        private final java.util.Map<Long, List<VectorSearchHit>> byKnowledgeBase = new java.util.HashMap<>();
        private int lastTopK;
        private double lastMinScore;
        private Collection<Long> lastDocumentIds;
        private int searchCalls;

        void hits(Long knowledgeBaseId, VectorSearchHit... hits) {
            byKnowledgeBase.put(knowledgeBaseId, List.of(hits));
        }

        @Override
        public List<VectorSearchHit> search(Long knowledgeBaseId, float[] queryEmbedding, int topK,
                                            double minScore, Collection<Long> documentIds) {
            lastTopK = topK;
            lastMinScore = minScore;
            lastDocumentIds = documentIds;
            searchCalls++;
            return byKnowledgeBase.getOrDefault(knowledgeBaseId, List.of());
        }

        @Override
        public void upsert(List<VectorRecord> records) {
            throw new UnsupportedOperationException("本测试不涉及写入");
        }

        @Override
        public Map<String, float[]> findCachedEmbeddings(Collection<String> contentHashes) {
            return Map.of();
        }

        @Override
        public void cacheEmbeddings(Map<String, float[]> embeddingsByHash, String model) {
            throw new UnsupportedOperationException("本测试不涉及写入");
        }

        @Override
        public void deleteByDocumentIds(Collection<Long> documentIds) {
            throw new UnsupportedOperationException("本测试不涉及写入");
        }

        @Override
        public void deleteByKnowledgeBase(Long knowledgeBaseId) {
            throw new UnsupportedOperationException("本测试不涉及写入");
        }

        @Override
        public long countByKnowledgeBase(Long knowledgeBaseId) {
            return byKnowledgeBase.getOrDefault(knowledgeBaseId, List.of()).size();
        }
    }
}
