package com.lky.nexus.app.infra.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 元数据过滤的查询构造测试（不需要 Elasticsearch）。
 *
 * <p><b>为什么这个测试值得存在</b>：本机没有 ES（Docker/WSL2 不可用），
 * "过滤真的在 ES 上生效"只能标 UNAVAILABLE。但"过滤条件有没有被拼进查询"
 * 是纯结构问题——如果连这步都不测，那么整个 ES 侧的过滤下推就只有"读代码"一种验证方式，
 * 而过滤失效的表现恰恰是不报错（返回了没筛过的结果）。
 *
 * <p>断言用结构化方式（{@code isTerms()/isBool()/isPrefix()}）而不是比对 JSON 字符串：
 * 字符串断言在客户端库升级、字段顺序变化时会无意义地失败，也说不清"到底哪里不对"。
 */
@DisplayName("ES 检索：元数据过滤下推到索引侧")
class ElasticsearchChunkIndexFilterTest {

    @Test
    @DisplayName("没有过滤条件：只留 match_all 占位，不产生任何多余子句")
    void noFiltersProducesMatchAll() {
        assertThat(ElasticsearchChunkIndex.metadataClauses(RetrievalFilters.none()).isMatchAll())
                .as("filter 上下文不允许传 null，用 match_all 占位（会被 ES 直接优化掉）")
                .isTrue();
        assertThat(ElasticsearchChunkIndex.metadataClauses(null).isMatchAll()).isTrue();
    }

    @Test
    @DisplayName("标签：terms 精确多值（any-of），标签是 keyword 字段不能被分词")
    void tagsBecomeTermsQuery() {
        Query query = ElasticsearchChunkIndex.metadataClauses(
                new RetrievalFilters(List.of("算法", "Java"), null, null));

        assertThat(query.isBool()).isTrue();
        List<Query> filters = query.bool().filter();
        assertThat(filters).hasSize(1);
        Query tagClause = filters.get(0);
        assertThat(tagClause.isTerms()).isTrue();
        assertThat(tagClause.terms().field()).isEqualTo("tags");
        assertThat(tagClause.terms().terms().value())
                .extracting(FieldValue::stringValue)
                .containsExactly("算法", "Java");
    }

    @Test
    @DisplayName("目录：term 精确 + prefix 子目录，且 minimum_should_match 显式为 1")
    void folderMatchesFolderAndSubfolders() {
        Query query = ElasticsearchChunkIndex.metadataClauses(
                new RetrievalFilters(null, "算法", null));

        Query folderClause = query.bool().filter().get(0);
        assertThat(folderClause.isBool()).as("目录条件是个 should 组合").isTrue();
        assertThat(folderClause.bool().should()).hasSize(2);
        assertThat(folderClause.bool().should().get(0).isTerm()).isTrue();
        assertThat(folderClause.bool().should().get(0).term().field()).isEqualTo("folder");
        assertThat(folderClause.bool().should().get(0).term().value().stringValue()).isEqualTo("算法");
        assertThat(folderClause.bool().should().get(1).isPrefix()).isTrue();
        assertThat(folderClause.bool().should().get(1).prefix().field()).isEqualTo("folder");
        assertThat(folderClause.bool().should().get(1).prefix().value())
                .as("子目录形如 算法/动态规划，因此前缀要带分隔符，不能写成 算法*")
                .isEqualTo("算法/");
        assertThat(folderClause.bool().minimumShouldMatch())
                .as("显式写 1：以后有人往这个 bool 里加 must 时，should 不会被静默降级成可选")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("指定文档：document_id 的 terms")
    void documentIdsBecomeTermsQuery() {
        Query query = ElasticsearchChunkIndex.metadataClauses(
                new RetrievalFilters(null, null, List.of(7L, 8L)));

        Query clause = query.bool().filter().get(0);
        assertThat(clause.isTerms()).isTrue();
        assertThat(clause.terms().field()).isEqualTo("document_id");
        assertThat(clause.terms().terms().value())
                .extracting(FieldValue::longValue)
                .containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("三个条件同时给：AND 关系，三个子句都在")
    void allConditionsAreCombinedWithAnd() {
        Query query = ElasticsearchChunkIndex.metadataClauses(
                new RetrievalFilters(List.of("算法"), "算法", List.of(7L)));

        List<Query> filters = query.bool().filter();
        assertThat(filters).as("bool.filter 之间就是 AND：标签/目录/文档三个条件必须同时满足")
                .hasSize(3);
        assertThat(filters.get(0).terms().field()).isEqualTo("tags");
        assertThat(filters.get(1).isBool()).isTrue();
        assertThat(filters.get(2).terms().field()).isEqualTo("document_id");
    }

    @Test
    @DisplayName("标签里的空串与纯空白被丢掉：否则会变成一个永远匹配不到的标签条件")
    void blankTagsAreDropped() {
        Query query = ElasticsearchChunkIndex.metadataClauses(
                new RetrievalFilters(List.of(" ", "", "算法"), null, null));

        assertThat(query.isBool()).isTrue();
        assertThat(query.bool().filter().get(0).terms().terms().value())
                .extracting(FieldValue::stringValue)
                .containsExactly("算法");
    }

    @Test
    @DisplayName("只剩空白标签时等价于没有条件：不能拼出一个空的 terms（空 terms 的行为随版本而变）")
    void onlyBlankTagsIsTreatedAsNoFilter() {
        assertThat(ElasticsearchChunkIndex.metadataClauses(
                new RetrievalFilters(List.of("  "), "  ", List.of())).isMatchAll()).isTrue();
    }
}
