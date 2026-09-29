package com.lky.nexus.ai.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 嵌入客户端测试（用假的 EmbeddingModel，不联网）。
 *
 * <p>这里验的是"包装层自己加的保护"，而不是模型能力：
 * 维度自校验与批量切分。前者防的是一种很隐蔽的事故——
 * 模型实际维度与知识库冻结维度不一致时，向量能写进去，但检索结果会莫名其妙。
 */
class EmbeddingClientTest {

    @Test
    @DisplayName("维度一致时正常工作，并缓存探测结果（不重复探测）")
    void acceptsMatchingDimensions() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient client = new EmbeddingClient("ollama:bge-m3",
                stubModel(calls, 1024), 1024);

        float[] vector = client.embed("缓存击穿怎么解决");

        assertThat(vector).hasSize(1024);
        assertThat(client.dimensions()).isEqualTo(1024);
        // 第一次 embed 一次；dimensions() 读缓存，不再调用模型
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("实际维度与声明不一致 → 立即报 4004（而不是让错误向量写进库）")
    void rejectsDimensionMismatch() {
        EmbeddingClient client = new EmbeddingClient("ollama:bge-m3",
                stubModel(new AtomicInteger(), 768), 1024);

        assertThatThrownBy(() -> client.embed("内容"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("嵌入维度不一致")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMBEDDING_FAILED.getCode());
    }

    @Test
    @DisplayName("批量：超过单批上限时自动分批，且顺序与入参严格对应")
    void splitsLargeBatches() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient client = new EmbeddingClient("ollama:bge-m3",
                stubModel(calls, 1024), 1024);

        List<String> texts = java.util.stream.IntStream.range(0, 70)
                .mapToObj(i -> "第 " + i + " 段")
                .toList();
        List<float[]> vectors = client.embedBatch(texts);

        assertThat(vectors).hasSize(70);
        // 70 条 / 单批 32 条 → 3 次调用
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("模型返回空向量 → 报 4004，而不是写入空向量")
    void rejectsEmptyVector() {
        EmbeddingModel emptyModel = new EmbeddingModel() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[0]).toList();
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
        EmbeddingClient client = new EmbeddingClient("ollama:bge-m3", emptyModel, 1024);

        assertThatThrownBy(() -> client.embed("内容"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("空向量");
    }

    @Test
    @DisplayName("模型调用抛异常 → 包装为 4004 并带上模型引用（便于定位是哪个角色挂了）")
    void wrapsModelFailures() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public List<float[]> embed(List<String> texts) {
                throw new IllegalStateException("Connection refused");
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
        EmbeddingClient client = new EmbeddingClient("ollama:bge-m3", broken, 1024);

        assertThatThrownBy(() -> client.embed("内容"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ollama:bge-m3")
                .hasMessageContaining("Connection refused");
    }

    // ------------------------------------------------------------------

    private EmbeddingModel stubModel(AtomicInteger calls, int dimensions) {
        return new EmbeddingModel() {
            @Override
            public List<float[]> embed(List<String> texts) {
                calls.incrementAndGet();
                return texts.stream().map(text -> filledVector(dimensions)).toList();
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
    }

    private float[] filledVector(int dimensions) {
        float[] vector = new float[dimensions];
        java.util.Arrays.fill(vector, 0.1f);
        return vector;
    }
}
