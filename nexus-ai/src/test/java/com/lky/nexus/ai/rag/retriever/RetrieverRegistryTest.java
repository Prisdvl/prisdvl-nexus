package com.lky.nexus.ai.rag.retriever;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 策略派发测试。
 *
 * <p>这里验的是两件"错了会静默变差"的事：
 * ① 请求的策略是否真的落到对的实现上（落到别的实现上不会报错，只会结果变差）；
 * ② 策略无人认领 / 被两个实现同时认领时，是不是<b>响亮地</b>失败——
 * 这两种情况都没有合理的默认行为可退。
 */
class RetrieverRegistryTest {

    @Test
    @DisplayName("派发：每个策略落到认领它的实现")
    void dispatchesToOwningRetriever() {
        StubRetriever vector = new StubRetriever("vector", RetrievalStrategy.VECTOR);
        StubRetriever keyword = new StubRetriever("keyword", RetrievalStrategy.KEYWORD);
        StubRetriever hybrid = new StubRetriever("hybrid", RetrievalStrategy.HYBRID);
        RetrieverRegistry registry = new RetrieverRegistry(List.of(vector, keyword, hybrid));

        assertThat(registry.get(RetrievalStrategy.VECTOR)).isSameAs(vector);
        assertThat(registry.get(RetrievalStrategy.KEYWORD)).isSameAs(keyword);
        assertThat(registry.get(RetrievalStrategy.HYBRID)).isSameAs(hybrid);
        assertThat(registry.supports(RetrievalStrategy.VECTOR)).isTrue();
        assertThat(registry.supports(RetrievalStrategy.HYBRID_RERANK)).isFalse();
        assertThat(registry.names()).containsExactlyInAnyOrder("vector", "keyword", "hybrid");
    }

    @Test
    @DisplayName("一个实现可以认领多个策略（如装饰器覆盖两种模式）")
    void oneRetrieverMayOwnSeveralStrategies() {
        StubRetriever both = new StubRetriever("hybrid", RetrievalStrategy.HYBRID,
                RetrievalStrategy.HYBRID_RERANK);
        RetrieverRegistry registry = new RetrieverRegistry(List.of(both));

        assertThat(registry.get(RetrievalStrategy.HYBRID)).isSameAs(both);
        assertThat(registry.get(RetrievalStrategy.HYBRID_RERANK)).isSameAs(both);
        assertThat(registry.names()).containsExactly("hybrid");
    }

    @Test
    @DisplayName("策略无人认领：抛 1001（而不是返回空结果，让上游误以为知识库是空的）")
    void unknownStrategyFailsLoudly() {
        RetrieverRegistry registry = new RetrieverRegistry(
                List.of(new StubRetriever("vector", RetrievalStrategy.VECTOR)));

        assertThatThrownBy(() -> registry.get(RetrievalStrategy.HYBRID_RERANK))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未启用")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("同一策略被两个实现认领：启动即失败（否则结果取决于加载顺序）")
    void duplicateClaimFailsAtStartup() {
        assertThatThrownBy(() -> new RetrieverRegistry(List.of(
                new StubRetriever("vector-a", RetrievalStrategy.VECTOR),
                new StubRetriever("vector-b", RetrievalStrategy.VECTOR))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VECTOR")
                .hasMessageContaining("vector-a")
                .hasMessageContaining("vector-b");
    }

    @Test
    @DisplayName("null 策略按默认策略处理")
    void nullStrategyFallsBackToDefault() {
        StubRetriever hybrid = new StubRetriever("hybrid", RetrievalStrategy.HYBRID);
        RetrieverRegistry registry = new RetrieverRegistry(List.of(hybrid));

        assertThat(registry.get(null)).isSameAs(hybrid);
        assertThat(registry.supports(null)).isFalse();
    }

    @Test
    @DisplayName("没有任何实现时：注册表可用，但取任何策略都报错（不静默）")
    void emptyRegistryStillFailsLoudly() {
        RetrieverRegistry registry = new RetrieverRegistry(List.of());

        assertThat(registry.names()).isEmpty();
        assertThatThrownBy(() -> registry.get(RetrievalStrategy.HYBRID))
                .isInstanceOf(BusinessException.class);
    }

    private record StubRetriever(String name, Set<RetrievalStrategy> strategies) implements Retriever {

        StubRetriever(String name, RetrievalStrategy... strategies) {
            this(name, Set.of(strategies));
        }

        @Override
        public List<RetrievedDocument> retrieve(RetrievalQuery query) {
            return List.of();
        }
    }
}
