package com.lky.nexus.app.vector;

import com.lky.nexus.app.support.LiveInfrastructure;
import com.lky.nexus.knowledge.vector.VectorRecord;
import com.lky.nexus.knowledge.vector.VectorSearchHit;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 适配器真实联调测试（自带 PostgreSQL + pgvector 容器，见 {@link LiveInfrastructure}）。
 *
 * <p>启用方式：{@code $env:AI_VECTOR_LIVE='true'; mvn -pl nexus-app -am test -Dtest=LiveVectorStoreTest}
 *
 * <p>它刻意<b>用构造向量而不是真实模型</b>：这样验证的是"SQL 与索引是否正确"，
 * 与"模型能不能用"解耦——两者出问题时的排查方向完全不同，
 * 混在一个测试里会互相掩盖。用单位向量还能精确断言排序（相似度≈1/0），
 * 比"看起来结果还行"强得多。
 *
 * <p>数据库是本次测试自己拉起的容器：既不再依赖开发者那套 5433 上的 pgvector
 * （里面可能有真实数据、也可能根本没起），也不必担心清理不干净——
 * 容器随测试结束一起消失。
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AI_VECTOR_LIVE", matches = "true")
class LiveVectorStoreTest {

    /**
     * pgvector 用测试自己的容器；MySQL 仍然必需（{@code @SpringBootTest} 的上下文要连业务库跑迁移）。
     * Redis / RabbitMQ 与向量读写无关，显式关掉（理由见 {@link LiveInfrastructure}）。
     */
    @DynamicPropertySource
    static void liveInfrastructure(DynamicPropertyRegistry registry) {
        LiveInfrastructure.registerMySql(registry);
        LiveInfrastructure.registerPgVector(registry);
        LiveInfrastructure.registerUnusedExternalDependencies(registry);
    }

    /** 用一个大到不会与真实数据冲突的 kbId，并在用例结束后清理。 */
    private static final Long TEST_KB_ID = 999_000_001L;

    /** 第二个知识库：用来验证"检索必须按 kb_id 过滤，不能跨库串数据"。 */
    private static final Long OTHER_KB_ID = 999_000_002L;

    private static final int DIMENSIONS = 1024;

    @Autowired
    private VectorStore vectorStore;

    @BeforeEach
    void cleanBefore() {
        // 用例开始前也清理一次：上一个用例若是失败退出，残留数据会污染下一个
        // （实际踩到过：上一次运行断言失败留下了行，导致 countByKnowledgeBase 多算）
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        // 注意按知识库清理：向量记录里 documentId 与 chunkId 在真实链路里是不同的值，
        // 用错 id 清理会"看起来删了、其实没删"，数据在用例之间累积
        vectorStore.deleteByKnowledgeBase(TEST_KB_ID);
        vectorStore.deleteByKnowledgeBase(OTHER_KB_ID);
    }

    @Test
    @DisplayName("写入 + 余弦检索：排序精确可断言，且强制按 kb_id 过滤")
    void upsertAndSearchByCosineSimilarity() {
        vectorStore.upsert(List.of(
                record(1L, TEST_KB_ID, axis(0)),
                record(2L, TEST_KB_ID, axis(1)),
                record(3L, TEST_KB_ID, mixed()),
                // 另一个知识库里的"完全相同的向量"：必须检索不到
                record(4L, OTHER_KB_ID, axis(0))));

        List<VectorSearchHit> hits = vectorStore.search(TEST_KB_ID, axis(0), 10, 0.0);

        // 期望排序：完全一致(1.0) > 混合(0.6) > 正交(0.0)
        assertThat(hits).extracting(VectorSearchHit::chunkId).containsExactly(1L, 3L, 2L);
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(hits.get(1).score()).as("mixed = 0.6*e0 + 0.8*e1，与 e0 的余弦相似度应为 0.6")
                .isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(hits.get(2).score()).as("正交向量相似度≈0").isCloseTo(0.0,
                org.assertj.core.data.Offset.offset(1e-5));
        assertThat(hits).extracting(VectorSearchHit::chunkId)
                .as("不能检索到别的知识库的向量")
                .doesNotContain(4L);
    }

    @Test
    @DisplayName("幂等写入：同一个 chunkId 重复写入只保留一行（两库无分布式事务，重试必须安全）")
    void upsertIsIdempotent() {
        vectorStore.upsert(List.of(record(11L, TEST_KB_ID, axis(0))));
        // 模拟"写向量成功、回写状态失败"后的重试：同一批再写一次，且向量内容变了
        vectorStore.upsert(List.of(record(11L, TEST_KB_ID, axis(2))));

        assertThat(vectorStore.countByKnowledgeBase(TEST_KB_ID)).isEqualTo(1);
        List<VectorSearchHit> hits = vectorStore.search(TEST_KB_ID, axis(2), 5, 0.0);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).as("应以最后一次写入的向量为准")
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    @DisplayName("相似度阈值与 topK 生效；删除按文档生效")
    void respectsScoreThresholdAndDeletion() {
        vectorStore.upsert(List.of(
                record(21L, TEST_KB_ID, axis(0)),
                record(22L, TEST_KB_ID, axis(1))));

        // 阈值 0.5 应把正交向量（相似度≈0）过滤掉
        assertThat(vectorStore.search(TEST_KB_ID, axis(0), 10, 0.5))
                .extracting(VectorSearchHit::chunkId).containsExactly(21L);

        vectorStore.deleteByKnowledgeBase(TEST_KB_ID);
        assertThat(vectorStore.countByKnowledgeBase(TEST_KB_ID)).isZero();
    }

    @Test
    @DisplayName("嵌入缓存：写入后可按内容哈希取回，且向量值不失真")
    void cachesEmbeddingsByHash() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        float[] original = mixed();
        vectorStore.cacheEmbeddings(Map.of(hash, original), "ollama:bge-m3");

        Map<String, float[]> cached = vectorStore.findCachedEmbeddings(List.of(hash));

        assertThat(cached).containsKey(hash);
        assertThat(cached.get(hash)).hasSize(DIMENSIONS);
        // 经过文本字面量往返后精度可能略有损失，但必须高度一致
        assertThat(cached.get(hash)[0]).isCloseTo(original[0],
                org.assertj.core.data.Offset.offset(1e-4f));
    }

    // ------------------------------------------------------------------

    /** 第 index 个坐标轴方向的单位向量（与查询向量正交时为 0 相似度）。 */
    private float[] axis(int index) {
        float[] vector = new float[DIMENSIONS];
        vector[index] = 1.0f;
        return vector;
    }

    /** 两个方向混合的非零向量（避免零向量在余弦距离下无定义）。 */
    private float[] mixed() {
        float[] vector = new float[DIMENSIONS];
        vector[0] = 0.6f;
        vector[1] = 0.8f;
        return vector;
    }

    private VectorRecord record(Long chunkId, Long kbId, float[] embedding) {
        return new VectorRecord(chunkId, kbId, chunkId, "hash-" + chunkId, embedding);
    }
}
