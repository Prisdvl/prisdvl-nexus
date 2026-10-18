package com.lky.nexus.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingByokPolicy;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.impl.ChunkEmbeddingServiceImpl;
import com.lky.nexus.knowledge.vector.VectorRecord;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化服务测试（Mockito，不依赖数据库与模型）。
 *
 * <p>重点验证三件"出错不会报错、只会让检索悄悄变差"的事：
 * ① 嵌入缓存是否真的省掉了模型调用；② 模型与知识库冻结模型不一致时是否拒绝执行；
 * ③ 写向量库的内容是否与切片严格对应。
 */
class ChunkEmbeddingServiceTest {

    private static final Long KB_ID = 1001L;
    private static final Long USER_ID = 7L;

    private KnowledgeBaseService knowledgeBaseService;
    private ChunkMapper chunkMapper;
    private VectorStore vectorStore;
    private EmbeddingClientFactory embeddingClientFactory;
    private AiModelProperties properties;

    private final AtomicInteger modelCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        // MyBatis-Plus 的 LambdaQueryWrapper 需要实体的元数据缓存（正常由运行时扫描注册）。
        // 纯单元测试里没有 MyBatis 上下文，必须手工初始化，否则构造 wrapper 会抛
        // "can not find lambda cache for this entity"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChunkPO.class);

        knowledgeBaseService = mock(KnowledgeBaseService.class);
        chunkMapper = mock(ChunkMapper.class);
        vectorStore = mock(VectorStore.class);
        embeddingClientFactory = mock(EmbeddingClientFactory.class);
        properties = new AiModelProperties();
        properties.setRoles(new HashMap<>(Map.of(
                AiModelProperties.Role.EMBEDDING_DEFAULT, "ollama:bge-m3")));
        // 统一桩好"知识库存在且属于该用户"：否则每个用例都要自己桩一遍，
        // 漏掉的那个会以 NPE 的形式失败（第一次跑就是这样）
        knowledgeBase();
    }

    @Test
    @DisplayName("缓存命中：内容相同的切片不重复调用模型，但向量照常写入")
    void usesEmbeddingCache() {
        ChunkPO cached = chunk(1L, "hash-a", "命中缓存的内容");
        ChunkPO fresh = chunk(2L, "hash-b", "需要新嵌入的内容");
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cached, fresh));
        // hash-a 已在缓存里（必须按当前模型引用查，否则会跨模型误命中）
        when(vectorStore.findCachedEmbeddings(anyCollection(), eq("ollama:bge-m3")))
                .thenReturn(Map.of("hash-a", vector(1024, 0.5f)));
        when(embeddingClientFactory.get(anyString())).thenReturn(client());

        ChunkEmbeddingService service = newService();
        ChunkEmbeddingService.EmbeddingResult result = service.embedPendingChunks(USER_ID, KB_ID, 100);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.embedded()).as("只有 1 条需要真正调用模型").isEqualTo(1);
        assertThat(result.cacheHits()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        // 两条向量都要入库（缓存命中的那条直接从缓存取向量）
        assertThat(capturedRecords()).hasSize(2);
        verify(vectorStore).findCachedEmbeddings(anyCollection(), eq("ollama:bge-m3"));
        verify(vectorStore).cacheEmbeddings(anyMap(), eq("ollama:bge-m3"));
    }

    @Test
    @DisplayName("同一批里内容重复：只嵌入一次（按 content_hash 去重）")
    void deduplicatesWithinBatch() {
        ChunkPO first = chunk(1L, "same-hash", "重复的内容");
        ChunkPO second = chunk(2L, "same-hash", "重复的内容");
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(vectorStore.findCachedEmbeddings(anyCollection(), anyString())).thenReturn(Map.of());
        when(embeddingClientFactory.get(anyString())).thenReturn(client());

        ChunkEmbeddingService service = newService();
        ChunkEmbeddingService.EmbeddingResult result = service.embedPendingChunks(USER_ID, KB_ID, 100);

        assertThat(result.embedded()).as("相同内容只调一次模型").isEqualTo(1);
        assertThat(capturedRecords()).hasSize(2);
    }

    @Test
    @DisplayName("知识库冻结的嵌入模型与当前配置不一致 → 拒绝执行（4006）")
    void rejectsModelMismatch() {
        KnowledgeBasePO knowledgeBase = knowledgeBase();
        knowledgeBase.setEmbeddingModel("text-embedding-3-small");
        knowledgeBase.setEmbeddingProvider("openai");
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);

        ChunkEmbeddingService service = newService();

        assertThatThrownBy(() -> service.embedPendingChunks(USER_ID, KB_ID, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冻结的嵌入模型")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMBEDDING_FROZEN_MISMATCH.getCode());
        verify(vectorStore, never()).upsert(any());
    }

    @Test
    @DisplayName("没有待处理切片时：不调用模型、不写库")
    void handlesEmptyPendingSet() {
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        ChunkEmbeddingService service = newService();
        ChunkEmbeddingService.EmbeddingResult result = service.embedPendingChunks(USER_ID, KB_ID, 100);

        assertThat(result.total()).isZero();
        assertThat(modelCalls.get()).isZero();
        verify(vectorStore, never()).upsert(any());
    }

    // ------------------------------------------------------------------

    private ChunkEmbeddingService newService() {
        // 模型一致性校验现在收敛在 KnowledgeEmbeddingResolver（写入与检索共用）。
        // 第 4 个参数是"用户自带嵌入凭据"端口：这里显式传 null = 只用全局角色
        // （纯单测没有 nexus-app 的数据库实现；生产由 Spring 注入）。
        var resolver = new KnowledgeEmbeddingResolver(properties, embeddingClientFactory,
                new EmbeddingByokPolicy(properties), null);
        return new ChunkEmbeddingServiceImpl(knowledgeBaseService, chunkMapper, vectorStore, resolver);
    }

    private List<VectorRecord> capturedRecords() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(captor.capture());
        return captor.getValue();
    }

    private EmbeddingClient client() {
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public List<float[]> embed(List<String> texts) {
                modelCalls.incrementAndGet();
                List<float[]> vectors = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    vectors.add(vector(1024, 0.1f * (i + 1)));
                }
                return vectors;
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public float[] embed(Document document) {
                throw new UnsupportedOperationException();
            }
        };
        return new EmbeddingClient("ollama:bge-m3", model, 1024);
    }

    private float[] vector(int dimensions, float value) {
        float[] vector = new float[dimensions];
        java.util.Arrays.fill(vector, value);
        return vector;
    }

    private ChunkPO chunk(Long id, String contentHash, String content) {
        ChunkPO chunk = new ChunkPO();
        chunk.setId(id);
        chunk.setKbId(KB_ID);
        chunk.setDocumentId(2001L);
        chunk.setContentHash(contentHash);
        chunk.setContent(content);
        chunk.setVectorStatus("PENDING");
        return chunk;
    }

    private KnowledgeBasePO knowledgeBase() {
        KnowledgeBasePO knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setEmbeddingProvider("ollama");
        knowledgeBase.setEmbeddingModel("bge-m3");
        knowledgeBase.setEmbeddingDim(1024);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);
        return knowledgeBase;
    }
}
