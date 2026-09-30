package com.lky.nexus.ai.rag.retriever;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索参数解析与夹取测试。
 *
 * <p>这一层看起来全是"无聊的边界处理"，但它是<b>契约的一部分</b>：
 * topK 上限、召回深度倍数、相似度默认值如果散在各个 Retriever 实现里，
 * 迟早出现两路不一致——而那种不一致不会有任何报错，
 * 只会让某一路悄悄多召回或少召回。
 */
class RetrievalQueryTest {

    @Test
    @DisplayName("策略解析：大小写不敏感，空值取默认，非法值报 1001")
    void parsesStrategy() {
        assertThat(RetrievalStrategy.parse(null)).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(RetrievalStrategy.parse("  ")).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(RetrievalStrategy.parse("vector")).isEqualTo(RetrievalStrategy.VECTOR);
        assertThat(RetrievalStrategy.parse(" Hybrid_Rerank ")).isEqualTo(RetrievalStrategy.HYBRID_RERANK);

        assertThatThrownBy(() -> RetrievalStrategy.parse("graph"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的检索策略")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("策略能力：重排的召回底座是 HYBRID，不是另一种召回方式")
    void strategyCapabilities() {
        assertThat(RetrievalStrategy.VECTOR.needsVector()).isTrue();
        assertThat(RetrievalStrategy.VECTOR.needsKeyword()).isFalse();
        assertThat(RetrievalStrategy.KEYWORD.needsVector()).isFalse();
        assertThat(RetrievalStrategy.KEYWORD.needsKeyword()).isTrue();
        assertThat(RetrievalStrategy.HYBRID.needsVector()).isTrue();
        assertThat(RetrievalStrategy.HYBRID.needsKeyword()).isTrue();

        assertThat(RetrievalStrategy.HYBRID.needsRerank()).isFalse();
        assertThat(RetrievalStrategy.HYBRID_RERANK.needsRerank()).isTrue();
        assertThat(RetrievalStrategy.HYBRID_RERANK.recallBase()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(RetrievalStrategy.VECTOR.recallBase()).isEqualTo(RetrievalStrategy.VECTOR);
    }

    @Test
    @DisplayName("topK 夹取到 [1,100]，召回深度 = min(200, limit × 3)")
    void clampsTopKAndDerivesDepth() {
        assertThat(query(0).limit()).as("0 也要能取到至少 1 条").isEqualTo(1);
        assertThat(query(-5).limit()).isEqualTo(1);
        assertThat(query(3).limit()).isEqualTo(3);
        assertThat(query(100).limit()).isEqualTo(100);
        assertThat(query(1000).limit()).isEqualTo(100);

        assertThat(query(1).candidateDepth()).isEqualTo(3);
        assertThat(query(5).candidateDepth()).as("默认每条返回背后有 3 条候选").isEqualTo(15);
        assertThat(query(66).candidateDepth()).as("198 未到上限").isEqualTo(198);
        assertThat(query(67).candidateDepth()).as("201 超过上限 200").isEqualTo(200);
        assertThat(query(1000).candidateDepth()).isEqualTo(200);
    }

    @Test
    @DisplayName("相似度下限：显式给正值就用它，<=0 时退回默认值")
    void resolvesSimilarityThreshold() {
        assertThat(query(5).effectiveSimilarityThreshold())
                .isEqualTo(RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(query(5, 0).effectiveSimilarityThreshold())
                .isEqualTo(RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(query(5, 0.7).effectiveSimilarityThreshold()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("重排查询：未单独指定时退回召回查询；指定了就用指定的（追问场景）")
    void resolvesRerankQuery() {
        assertThat(query(5).effectiveRerankQuery()).isEqualTo("问题");
        assertThat(new RetrievalQuery("上一轮问题 问题", "  ", 1L, 2L, List.of(3L), 5,
                RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD, null, RetrievalStrategy.HYBRID_RERANK)
                .effectiveRerankQuery()).isEqualTo("上一轮问题 问题");
        assertThat(new RetrievalQuery("上一轮问题 问题", "问题", 1L, 2L, List.of(3L), 5,
                RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD, null, RetrievalStrategy.HYBRID_RERANK)
                .effectiveRerankQuery()).isEqualTo("问题");
    }

    @Test
    @DisplayName("归一化：null 集合/条件/策略都收敛成安全值，调用方不必判空")
    void normalizesNulls() {
        RetrievalQuery normalized = new RetrievalQuery("q", null, 1L, null, null, 5, 0, null, null);

        assertThat(normalized.knowledgeBaseIds()).isEmpty();
        assertThat(normalized.filters()).isEqualTo(RetrievalFilters.none());
        assertThat(normalized.filters().isEmpty()).isTrue();
        assertThat(normalized.strategy()).isEqualTo(RetrievalStrategy.DEFAULT);
    }

    @Test
    @DisplayName("空查询不该走到召回层")
    void detectsBlankQuery() {
        assertThat(query(5).hasQuery()).isTrue();
        assertThat(new RetrievalQuery("   ", null, 1L, null, List.of(), 5, 0, null,
                RetrievalStrategy.HYBRID).hasQuery()).isFalse();
        assertThat(new RetrievalQuery(null, null, 1L, null, List.of(), 5, 0, null,
                RetrievalStrategy.HYBRID).hasQuery()).isFalse();
    }

    @Test
    @DisplayName("过滤条件：null 与空集合等价，都是\"不过滤\"")
    void normalizesFilters() {
        assertThat(new RetrievalFilters(null, null, null)).isEqualTo(RetrievalFilters.none());
        assertThat(new RetrievalFilters(null, null, null).isEmpty()).isTrue();
        assertThat(new RetrievalFilters(null, "  ", List.of()).isEmpty()).isTrue();

        RetrievalFilters filters = new RetrievalFilters(List.of("算法"), "notes/ai", List.of(1L, 2L));
        assertThat(filters.isEmpty()).isFalse();
        assertThat(filters.tags()).containsExactly("算法");
        assertThat(filters.folder()).isEqualTo("notes/ai");
        assertThat(filters.documentIds()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("单库构造器：策略与默认值都落到正确的位置")
    void singleKnowledgeBaseFactory() {
        RetrievalQuery query = RetrievalQuery.of("缓存击穿", 7L, 9L, 1001L, 5, RetrievalStrategy.HYBRID);

        assertThat(query.knowledgeBaseIds()).containsExactly(1001L);
        assertThat(query.userId()).isEqualTo(7L);
        assertThat(query.workspaceId()).isEqualTo(9L);
        assertThat(query.filters().isEmpty()).isTrue();
        assertThat(query.effectiveSimilarityThreshold())
                .isEqualTo(RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD);
    }

    private static RetrievalQuery query(int topK) {
        return query(topK, 0);
    }

    private static RetrievalQuery query(int topK, double threshold) {
        return new RetrievalQuery("问题", null, 1L, 2L, List.of(3L), topK, threshold, null,
                RetrievalStrategy.HYBRID);
    }
}
