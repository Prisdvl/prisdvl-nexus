package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索参数解析（Phase 3.2）：优先级与兜底。
 *
 * <p>这一组用例都很小，但它们钉住的是本项目栽过的一种坑——
 * <b>同一件事的默认值散在多处，各自漂移</b>：建库默认 0.7、检索实际用 0.25，
 * 于是"新知识库几乎召不回内容"且没有任何报错。所以：
 * 优先级要明确，兜底要唯一，越界要夹取（而不是抛错把功能打死）。
 */
class RetrievalQueryFactoryTest {

    private RetrievalDefaults defaults;
    private RetrievalQueryFactory factory;

    @BeforeEach
    void setUp() {
        defaults = new RetrievalDefaults();
        factory = new RetrievalQueryFactory(defaults);
    }

    @Test
    @DisplayName("优先级：调用方显式指定 > 知识库配置 > 全局默认")
    void requestWinsOverKnowledgeBaseOverGlobalDefault() {
        KnowledgeBasePO knowledgeBase = knowledgeBase(4, 0.42);

        // 三层都在：取最高层
        assertThat(factory.resolveTopK(9, knowledgeBase)).isEqualTo(9);
        assertThat(factory.resolveThreshold(0.9, knowledgeBase)).isEqualTo(0.9);

        // 请求没指定：取知识库
        assertThat(factory.resolveTopK(null, knowledgeBase)).isEqualTo(4);
        assertThat(factory.resolveThreshold(null, knowledgeBase)).isEqualTo(0.42);
    }

    @Test
    @DisplayName("知识库没配（null）：取全局默认，且确实读的是配置值而不是写死的数字")
    void fallsBackToGlobalDefaults() {
        defaults.setTopK(7);
        defaults.setSimilarityThreshold(0.31);
        KnowledgeBasePO knowledgeBase = knowledgeBase(null, null);

        assertThat(factory.resolveTopK(null, knowledgeBase)).isEqualTo(7);
        assertThat(factory.resolveThreshold(null, knowledgeBase)).isEqualTo(0.31);
        // 全局默认本身也不能是 0，否则等于"没有下限"
        assertThat(factory.resolveThreshold(null, null)).isEqualTo(0.31);
        assertThat(factory.resolveTopK(null, null)).isEqualTo(7);
    }

    @Test
    @DisplayName("默认值出厂值：topK 5、阈值 0.25（与建库默认、与改造前实际生效值同一套）")
    void shippedDefaultsAreUnified() {
        assertThat(new RetrievalDefaults().getTopK()).isEqualTo(RetrievalQuery.DEFAULT_TOP_K);
        assertThat(new RetrievalDefaults().getSimilarityThreshold())
                .isEqualTo(RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD);
    }

    @Test
    @DisplayName("越界取值一律夹取：[1, 100]；阈值非正数视为\"没配\"")
    void clampsInsteadOfFailing() {
        assertThat(factory.resolveTopK(0, null)).as("0 仍按至少 1 条（保持旧的夹取语义）").isEqualTo(1);
        assertThat(factory.resolveTopK(-3, null)).isEqualTo(1);
        assertThat(factory.resolveTopK(9999, null)).isEqualTo(RetrievalQuery.MAX_TOP_K);

        assertThat(factory.resolveThreshold(0.0, null)).isEqualTo(RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(factory.resolveThreshold(-1.0, null)).isEqualTo(RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(factory.resolveThreshold(0.0, knowledgeBase(5, 0.0)))
                .isEqualTo(RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD);
    }

    @Test
    @DisplayName("组装出的查询带上归属与目标库：userId / workspaceId / knowledgeBaseIds 一个都不能少")
    void queryCarriesOwnershipAndTarget() {
        KnowledgeBasePO knowledgeBase = knowledgeBase(4, 0.42);
        knowledgeBase.setId(1001L);
        knowledgeBase.setWorkspaceId(9L);

        RetrievalQuery query = factory.forKnowledgeBase(knowledgeBase, "问题", "原问题", null, null,
                7L, RetrievalStrategy.HYBRID);

        assertThat(query.query()).isEqualTo("问题");
        assertThat(query.rerankQuery()).as("重排用原问题，召回用消解指代后的语句").isEqualTo("原问题");
        assertThat(query.userId()).isEqualTo(7L);
        assertThat(query.workspaceId()).isEqualTo(9L);
        assertThat(query.knowledgeBaseIds()).containsExactly(1001L);
        assertThat(query.strategy()).isEqualTo(RetrievalStrategy.HYBRID);
        assertThat(query.limit()).isEqualTo(4);
        assertThat(query.candidateDepth()).isEqualTo(12);
        assertThat(query.effectiveSimilarityThreshold()).isEqualTo(0.42);
    }

    private static KnowledgeBasePO knowledgeBase(Integer topK, Double threshold) {
        KnowledgeBasePO knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setRetrievalTopK(topK);
        knowledgeBase.setSimilarityThreshold(threshold);
        return knowledgeBase;
    }
}
