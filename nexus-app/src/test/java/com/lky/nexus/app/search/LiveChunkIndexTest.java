package com.lky.nexus.app.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.IndexedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 检索索引真实联调测试（需要 docker 里的 Elasticsearch + IK 插件）。
 *
 * <p>启用方式：{@code $env:AI_SEARCH_LIVE='true'; mvn -pl nexus-app test -Dtest=LiveChunkIndexTest}
 *
 * <p>最关键的断言是第一条：<b>直接调用 ES 的 _analyze 接口看分词结果</b>，
 * 证明 IK 分析器真的生效了。中文检索"看起来能用"是个陷阱——
 * 用标准分析器时中文会被切成单字，短查询也能命中，但长句检索的准确度会明显变差，
 * 而这一点在功能测试里很难暴露。分词器必须被直接验证，而不是间接推断。
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AI_SEARCH_LIVE", matches = "true")
class LiveChunkIndexTest {

    private static final Long TEST_KB_ID = 999_100_001L;
    private static final Long OTHER_KB_ID = 999_100_002L;

    @Autowired
    private ChunkIndex chunkIndex;

    @Autowired
    private ElasticsearchClient client;

    @Value("${nexus.search.index-alias}")
    private String alias;

    @BeforeEach
    void prepareIndex() {
        chunkIndex.ensureIndex();
        chunkIndex.deleteByKnowledgeBase(TEST_KB_ID);
        chunkIndex.deleteByKnowledgeBase(OTHER_KB_ID);
    }

    @AfterEach
    void cleanUp() {
        chunkIndex.deleteByKnowledgeBase(TEST_KB_ID);
        chunkIndex.deleteByKnowledgeBase(OTHER_KB_ID);
    }

    @Test
    @DisplayName("IK 分析器真实生效：中文按词典切词，而不是切成单字")
    void usesIkAnalyzer() throws Exception {
        var response = client.indices().analyze(a -> a
                .index(alias)
                .analyzer("nexus_index_analyzer")
                .text("缓存击穿与互斥锁"));

        List<String> tokens = response.tokens().stream().map(t -> t.token()).toList();

        // IK 会按词典切出"缓存""击穿"这类词；标准分析器只会给出单字（缓/存/击/穿）
        assertThat(tokens).contains("缓存", "击穿");
        assertThat(tokens).as("若被切成单字说明 IK 未生效：%s", tokens).doesNotContain("缓");
    }

    @Test
    @DisplayName("中文检索：命中相关切片并保持相关性排序")
    void searchesChineseByBm25() {
        indexAndRefresh(List.of(
                chunk(1L, TEST_KB_ID, "Redis 缓存", "缓存击穿",
                        "缓存击穿是指热点 key 失效后大量请求打到数据库，可用互斥锁解决。"),
                chunk(2L, TEST_KB_ID, "JVM", "垃圾回收",
                        "G1 收集器按 Region 划分堆内存，适合大堆场景。"),
                chunk(3L, TEST_KB_ID, "MySQL", "索引优化",
                        "联合索引要遵循最左前缀原则，避免索引失效。")));

        List<ChunkSearchHit> hits = chunkIndex.search(TEST_KB_ID, "缓存击穿怎么解决", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId()).as("应命中缓存相关切片").isEqualTo(1L);
        assertThat(hits.get(0).source()).isEqualTo("bm25");
        assertThat(hits.get(0).score()).isPositive();
    }

    @Test
    @DisplayName("字段权重：标题命中排在正文命中之前")
    void titleBoostAffectsRanking() {
        indexAndRefresh(List.of(
                // 标题含"索引下推"
                chunk(11L, TEST_KB_ID, "数据库优化", "索引下推", "这是一段与主题无关的正文内容。"),
                // 正文含"索引下推"
                chunk(12L, TEST_KB_ID, "数据库优化", "查询优化",
                        "索引下推可以把过滤条件下推到存储引擎层，减少回表。")));

        List<ChunkSearchHit> hits = chunkIndex.search(TEST_KB_ID, "索引下推", 5);

        assertThat(hits).hasSizeGreaterThanOrEqualTo(2);
        assertThat(hits.get(0).chunkId()).as("标题权重 3 倍，应排在前面").isEqualTo(11L);
    }

    @Test
    @DisplayName("按知识库过滤：不串库；删除按知识库生效")
    void filtersByKnowledgeBase() {
        indexAndRefresh(List.of(
                chunk(21L, TEST_KB_ID, "我的笔记", null, "向量检索与关键词检索的融合方式。"),
                chunk(22L, OTHER_KB_ID, "别人的笔记", null, "向量检索与关键词检索的融合方式。")));

        List<ChunkSearchHit> hits = chunkIndex.search(TEST_KB_ID, "向量检索 融合", 10);

        assertThat(hits).extracting(ChunkSearchHit::chunkId).contains(21L).doesNotContain(22L);
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isEqualTo(1);

        chunkIndex.deleteByKnowledgeBase(TEST_KB_ID);
        // 删除同样要等 refresh 才对 search/count 可见（与写入是同一套原因）
        refresh();
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isZero();
        assertThat(chunkIndex.countByKnowledgeBase(OTHER_KB_ID)).as("删自己的不该影响别人").isEqualTo(1);
    }

    @Test
    @DisplayName("重建索引：别名原子切换，旧索引被清理，检索不中断")
    void recreateIndexSwitchesAlias() {
        indexAndRefresh(List.of(
                chunk(31L, TEST_KB_ID, "标题", null, "重建索引之前写入的内容。")));
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isEqualTo(1);

        chunkIndex.recreateIndex();

        // 别名仍然可用（原子切换的关键意义），但新索引是空的
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isZero();
        assertThat(chunkIndex.search(TEST_KB_ID, "重建", 5)).isEmpty();

        // 重建后能继续写入
        indexAndRefresh(List.of(
                chunk(32L, TEST_KB_ID, "标题", null, "重建索引之后写入的内容。")));
        assertThat(chunkIndex.search(TEST_KB_ID, "重建索引之后", 5))
                .extracting(ChunkSearchHit::chunkId).contains(32L);
    }

    // ------------------------------------------------------------------

    /**
     * 写入后显式 refresh。
     *
     * <p>ES 默认 {@code refresh_interval=1s}：写入的文档在 refresh 之前对 search/count <b>不可见</b>。
     * 生产环境不该为每次写入等待 refresh（那会把写入吞吐压到每秒一次），
     * 但测试需要确定性，因此在这里显式刷新——"刚写完就查不到"是 ES 的正常行为，不是 bug。
     */
    private void indexAndRefresh(List<IndexedChunk> chunks) {
        chunkIndex.indexChunks(chunks);
        refresh();
    }

    private void refresh() {
        try {
            client.indices().refresh(r -> r.index(alias));
        } catch (IOException ex) {
            throw new IllegalStateException("refresh 失败", ex);
        }
    }

    private IndexedChunk chunk(Long chunkId, Long kbId, String title, String headingPath, String content) {
        return new IndexedChunk(chunkId, kbId, chunkId, 1L, title, headingPath, content,
                List.of("java"), "notes", 0);
    }
}
