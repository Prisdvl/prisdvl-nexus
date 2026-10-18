package com.lky.nexus.app.ai;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实嵌入模型验收测试（用百炼 {@code text-embedding-v4}，默认跳过）。
 *
 * <p>启用方式（与 test profile 的 {@code embedding.default} 一致）：
 * <pre>
 * $env:AI_EMBEDDING_API_KEY='&lt;百炼 Key&gt;'   # 缺省时用假 Key，本类不会跑
 * $env:AI_EMBEDDING_LIVE='true'; mvn -pl nexus-app test -Dtest=LiveEmbeddingTest
 * </pre>
 *
 * <p>曾经这条路要求本机 Ollama + {@code bge-m3}（现已完全弃用）；代码里的 OLLAMA 协议支持仍保留，
 * 只是不再是默认选型，也不再被测试依赖。
 *
 * <p>它验证的不只是"能不能返回向量"，还包括<b>向量是否真的表达了语义</b>——
 * 这一条比任何维度断言都重要：如果模型没加载对（例如返回了全零向量），
 * 维度检查照样通过，但检索会完全失效。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AI_EMBEDDING_LIVE", matches = "true")
class LiveEmbeddingTest {

    @Autowired
    private EmbeddingClientFactory embeddingClientFactory;

    @Test
    @DisplayName("百炼 text-embedding-v4 真实调用：1024 维、中文可用、语义相似度排序正确")
    void embedsRealText() {
        EmbeddingClient client = embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT);

        float[] cacheBreakdown = client.embed("Redis 缓存击穿是指某个热点 key 失效，大量请求直接打到数据库");
        float[] mutexSolution = client.embed("用互斥锁保证只有一个请求去数据库加载数据，其余请求等待");
        float[] unrelated = client.embed("今天天气不错，适合出门散步");

        assertThat(cacheBreakdown).hasSize(1024);
        assertThat(client.dimensions()).isEqualTo(1024);

        // 全零向量说明模型没真正工作（维度检查会放过它，检索却会失效）
        assertThat(nonZeroRatio(cacheBreakdown)).as("向量不应全为零").isGreaterThan(0.9);

        // 语义检查：同主题两句话的相似度必须高于无关句子
        double related = cosine(cacheBreakdown, mutexSolution);
        double unrelatedScore = cosine(cacheBreakdown, unrelated);
        assertThat(related)
                .as("同主题相似度 %.4f 应显著高于无关 %.4f", related, unrelatedScore)
                .isGreaterThan(unrelatedScore + 0.1);
    }

    @Test
    @DisplayName("批量嵌入：顺序与数量严格对应")
    void embedsBatch() {
        EmbeddingClient client = embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT);

        List<String> texts = List.of("Spring Boot 自动配置", "JVM 垃圾回收", "MySQL 索引优化");
        List<float[]> vectors = client.embedBatch(texts);

        assertThat(vectors).hasSize(3);
        assertThat(vectors).allSatisfy(vector -> assertThat(vector).hasSize(1024));
        // 同样文本两次嵌入应当高度一致（模型是确定性的）
        assertThat(cosine(vectors.get(0), client.embed(texts.get(0)))).isGreaterThan(0.99);
    }

    private double nonZeroRatio(float[] vector) {
        long nonZero = 0;
        for (float value : vector) {
            if (value != 0f) {
                nonZero++;
            }
        }
        return (double) nonZero / vector.length;
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
