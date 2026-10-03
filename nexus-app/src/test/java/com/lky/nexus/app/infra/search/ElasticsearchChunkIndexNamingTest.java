package com.lky.nexus.app.infra.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 索引命名的纯函数测试（不需要 Elasticsearch）。
 *
 * <p><b>为什么值得单独测</b>："同一秒内连续重建两次"曾经是会真实报 500 的缺陷：
 * 物理索引名只到秒（{@code yyyyMMddHHmmss}），第二次重建算出同一个名字，
 * {@code create} 撞 {@code resource_already_exists_exception}，接口 500——
 * 而更糟的是当时切片已经被标成"已重建"，索引其实是空的。
 * 这个缺陷的全部根因就是一个名字函数，因此它可以在没有 ES 的机器上被钉死。
 */
@DisplayName("ES 检索：索引命名（按知识库隔离 + 同秒不重名）")
class ElasticsearchChunkIndexNamingTest {

    private static final String BASE_ALIAS = "nexus_chunk";

    private ElasticsearchChunkIndex adapter() {
        // 只用到命名逻辑，客户端不会被触碰
        return new ElasticsearchChunkIndex(null, BASE_ALIAS, "ik_max_word", "ik_smart");
    }

    @Test
    @DisplayName("别名按知识库区分：重建某一个库碰不到另一个库的索引")
    void aliasIsPerKnowledgeBase() {
        ElasticsearchChunkIndex adapter = adapter();

        assertThat(adapter.aliasFor(1001L)).isEqualTo("nexus_chunk_1001");
        assertThat(adapter.aliasFor(1002L)).isEqualTo("nexus_chunk_1002");
        assertThat(adapter.aliasFor(1001L))
                .as("不同知识库必须落到不同的索引上，否则重建会互相波及")
                .isNotEqualTo(adapter.aliasFor(1002L));
    }

    @Test
    @DisplayName("同一秒内连续重建：序号保证物理索引名不重名")
    void physicalNamesAreUniqueWithinTheSameSecond() {
        LocalDateTime sameSecond = LocalDateTime.of(2026, 10, 2, 21, 49, 30);

        List<String> names = IntStream.range(0, 1000)
                .mapToObj(seq -> ElasticsearchChunkIndex.physicalIndexName("nexus_chunk_1001", sameSecond, seq))
                .toList();

        Set<String> distinct = new HashSet<>(names);
        assertThat(distinct)
                .as("1000 次同秒重建必须给出 1000 个不同的索引名，否则第二次就会撞 resource_already_exists")
                .hasSize(1000);
        assertThat(names.get(0)).startsWith("nexus_chunk_1001_20261002214930_");
    }

    @Test
    @DisplayName("物理索引名带知识库别名前缀：通配清理不会跨库误伤")
    void physicalNameIsScopedToItsKnowledgeBase() {
        LocalDateTime sameSecond = LocalDateTime.of(2026, 10, 2, 21, 49, 30);

        String forKb1001 = ElasticsearchChunkIndex.physicalIndexName("nexus_chunk_1001", sameSecond, 0);
        String forKb1002 = ElasticsearchChunkIndex.physicalIndexName("nexus_chunk_1002", sameSecond, 0);

        assertThat(forKb1001).startsWith("nexus_chunk_1001_");
        // "nexus_chunk_1002_..." 不会被 "nexus_chunk_1001_*" 匹配到，
        // 这正是 clear/delete 只作用于本库的前提
        assertThat(forKb1002).doesNotStartWith("nexus_chunk_1001_");
    }
}
