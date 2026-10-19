package com.lky.nexus.app.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.lky.nexus.app.infra.search.ElasticsearchChunkIndex;
import com.lky.nexus.app.support.LiveInfrastructure;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 检索索引真实联调测试（自带 Elasticsearch + IK 容器，见 {@link LiveInfrastructure}）。
 *
 * <p>启用方式：{@code $env:AI_SEARCH_LIVE='true'; mvn -pl nexus-app -am test -Dtest=LiveChunkIndexTest}
 *
 * <p>最关键的断言是第一条：<b>直接调用 ES 的 _analyze 接口看分词结果</b>，
 * 证明 IK 分析器真的生效了。中文检索"看起来能用"是个陷阱——
 * 用标准分析器时中文会被切成单字，短查询也能命中，但长句检索的准确度会明显变差，
 * 而这一点在功能测试里很难暴露。分词器必须被直接验证，而不是间接推断。
 *
 * <p><b>它不会碰到任何人正在使用的数据</b>，两层保障：
 * <ol>
 *   <li>ES 是本次测试**自己拉起的容器**（用完销毁），连的不是开发栈那一份；</li>
 *   <li>索引按知识库分片（索引名 {@code <alias>_<kbId>_<时间戳>_<序号>}），
 *       用例只用两个刻意挑的、不会与真实数据重合的固定 id，
 *       收尾时把这两份物理索引整个删掉。</li>
 * </ol>
 * 早先它清空的是所有知识库共用的那一份索引——对着开发栈跑一次，BM25 那条腿就静默失效了。
 * 第 1 条是 Phase 5.9 补上的：只靠第 2 条，测试仍然依赖"你本机那套容器此刻是什么状态"。
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AI_SEARCH_LIVE", matches = "true")
class LiveChunkIndexTest {

    /**
     * 外部依赖各自归位：MySQL 与 ES 用测试自己的容器，
     * Redis / RabbitMQ / 向量库与本次要验的东西无关，显式关掉
     * （每一条的理由见 {@link LiveInfrastructure}）。
     *
     * <p>方法在**上下文创建之前**执行，因此这里改的属性就是应用真正读到的属性。
     */
    @DynamicPropertySource
    static void liveInfrastructure(DynamicPropertyRegistry registry) {
        LiveInfrastructure.registerMySql(registry);
        LiveInfrastructure.registerElasticsearch(registry);
        LiveInfrastructure.registerVectorServerDisabled(registry);
        LiveInfrastructure.registerUnusedExternalDependencies(registry);
    }

    private static final Long TEST_KB_ID = 999_100_001L;
    private static final Long OTHER_KB_ID = 999_100_002L;

    @Autowired
    private ElasticsearchChunkIndex chunkIndex;

    @Autowired
    private ElasticsearchClient client;

    @Value("${nexus.search.index-alias}")
    private String alias;

    @BeforeEach
    void prepareIndex() {
        // 先清掉上一次可能留下的索引（同秒重跑时会撞名，且旧文档不该影响本次断言），
        // 再建一份干净的——_analyze 那条用例需要一个存在的物理索引。
        dropIndexes();
        chunkIndex.ensureIndex(TEST_KB_ID);
    }

    @AfterEach
    void cleanUp() {
        dropIndexes();
    }

    @Test
    @DisplayName("IK 分析器真实生效：中文按词典切词，而不是切成单字")
    void usesIkAnalyzer() throws Exception {
        // 先取出来：builder 的 lambda 不允许抛受检异常（getAlias 会抛 IOException）
        String physical = physicalIndexOf(TEST_KB_ID);

        var response = client.indices().analyze(a -> a
                .index(physical)
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

        chunkIndex.recreateIndex(TEST_KB_ID);

        // 别名仍然可用（原子切换的关键意义），但新索引是空的
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isZero();
        assertThat(chunkIndex.search(TEST_KB_ID, "重建", 5)).isEmpty();

        // 重建后能继续写入
        indexAndRefresh(List.of(
                chunk(32L, TEST_KB_ID, "标题", null, "重建索引之后写入的内容。")));
        assertThat(chunkIndex.search(TEST_KB_ID, "重建索引之后", 5))
                .extracting(ChunkSearchHit::chunkId).contains(32L);
    }

    @Test
    @DisplayName("重建只影响自己那个知识库：另一个库既没被清空，也依然检索得到")
    void recreateIndexOnlyAffectsItsOwnKnowledgeBase() {
        indexAndRefresh(List.of(
                chunk(41L, TEST_KB_ID, "A 的笔记", null, "缓存击穿之后该怎么处理。"),
                chunk(42L, OTHER_KB_ID, "B 的笔记", null, "缓存击穿之后该怎么处理。")));
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isEqualTo(1);
        assertThat(chunkIndex.countByKnowledgeBase(OTHER_KB_ID)).isEqualTo(1);

        chunkIndex.recreateIndex(TEST_KB_ID);

        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).as("被重建的库应当清空").isZero();
        assertThat(chunkIndex.search(TEST_KB_ID, "缓存击穿", 5)).isEmpty();

        // 这条是本次修复的核心断言：另一个知识库的数据与可检索性都不受影响。
        // 修复前两者共用一份索引，这里 OTHER 会一起变空，而它的 MySQL 切片状态仍是 DONE，
        // 于是它的 BM25 检索永远静默失效。
        assertThat(chunkIndex.countByKnowledgeBase(OTHER_KB_ID))
                .as("别的知识库不该被这次重建清空").isEqualTo(1);
        assertThat(chunkIndex.search(OTHER_KB_ID, "缓存击穿", 5))
                .as("别的知识库应当依然检索得到").extracting(ChunkSearchHit::chunkId).contains(42L);
    }

    @Test
    @DisplayName("本库还没有索引时重建：建出可用的别名，而不是 404 报错")
    void recreateIndexWorksWhenNoIndexExistsYet() throws Exception {
        // 先抹掉 @BeforeEach 建的那一份：模拟两种真实状态——**新建的空库**，
        // 以及**升级前入库、还没按库建过索引的库**（Phase 5.9 起两者都是常态）。
        dropIndexes();

        // 修复前这里抛 ElasticsearchException[404 alias missing]：currentIndexBehindAlias
        // 直接 getAlias，而它既不是空响应、也不是 IOException，于是绕过 catch (IOException)
        // 冒成 500/9999，同时让下面那个 `oldIndex == null` 分支永远走不到。
        // 这条路径不只属于升级——用户在空库上点一次「重建索引」就会撞上。
        chunkIndex.recreateIndex(TEST_KB_ID);

        assertThat(physicalIndexOf(TEST_KB_ID)).as("重建必须把别名建出来").isNotBlank();

        // 建完照常可写可查（别名指向的是一份真实可用的索引，不是一个空壳）
        indexAndRefresh(List.of(
                chunk(61L, TEST_KB_ID, "标题", null, "重建之前这个库还没有索引。")));
        assertThat(chunkIndex.search(TEST_KB_ID, "还没有索引", 5))
                .extracting(ChunkSearchHit::chunkId).contains(61L);
    }

    @Test
    @DisplayName("同一秒内连续重建两次不会重名（序号兜住秒级时间戳）")
    void recreateIndexIsRepeatableWithinTheSameSecond() {
        indexAndRefresh(List.of(
                chunk(51L, TEST_KB_ID, "标题", null, "第一次重建之前的内容。")));

        // 中间不做任何等待：只用 yyyyMMddHHmmss 时这两次会算出同一个物理索引名，
        // 第二次撞 resource_already_exists_exception 并抛 500。
        chunkIndex.recreateIndex(TEST_KB_ID);
        chunkIndex.recreateIndex(TEST_KB_ID);

        // 别名仍然指向一份可用的空索引，并且能继续写入
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isZero();
        indexAndRefresh(List.of(
                chunk(52L, TEST_KB_ID, "标题", null, "第二次重建之后的内容。")));
        assertThat(chunkIndex.search(TEST_KB_ID, "第二次重建之后", 5))
                .extracting(ChunkSearchHit::chunkId).contains(52L);
    }

    @Test
    @DisplayName("按文档 id 跨库删除：只删掉这些文档的切片，别的文档与别的库都不动")
    void deletesByDocumentIdsAcrossKnowledgeBases() {
        // 这条用例补的是一个"零真实覆盖"的生产代码路径：deleteByDocumentIds 走的是
        // 通配索引上的 _delete_by_query（索引按库分片，而调用方只拿得到文档 id）。
        // 它与"删索引"是两套 API、两套限制，因此删索引的用例通过并不代表它能用。
        // 三段正文刻意用互不相干的词：BM25 只看词，若三段都写"文档/切片"这种共同词，
        // 删掉的那段仍会被别的段命中，这条用例就变成了自我欺骗（实测踩过：以为删干净了，
        // 其实匹配到的是"保留"那条）
        indexAndRefresh(List.of(
                chunkOfDocument(71L, TEST_KB_ID, 700L, "缓存击穿可以用互斥锁解决。"),
                chunkOfDocument(72L, TEST_KB_ID, 701L, "垃圾回收器的分代假设。"),
                chunkOfDocument(73L, OTHER_KB_ID, 700L, "缓存击穿可以用互斥锁解决。")));

        // 同一个 documentId 在两个库里都存在：这个方法只按 document_id 删，
        // 因此两个库里的 700 都会没——"跨库"两个字就是这个意思。
        // 它成立的前提是 document_id 在业务库里全局唯一（MyBatis-Plus 的雪花 id 满足），
        // 换成"每个库各自从 1 开始编号"就会误删
        chunkIndex.deleteByDocumentIds(List.of(700L));
        refresh();

        assertThat(chunkIndex.search(TEST_KB_ID, "缓存击穿", 5)).isEmpty();
        assertThat(chunkIndex.search(TEST_KB_ID, "垃圾回收器", 5))
                .extracting(ChunkSearchHit::chunkId).containsExactly(72L);
        assertThat(chunkIndex.search(OTHER_KB_ID, "缓存击穿", 5)).isEmpty();
    }

    @Test
    @DisplayName("删索引：物理索引与别名一起消失，别的库不受影响，重复删不报错")
    void deleteIndexForRemovesIndexAndAlias() throws Exception {
        // 这条用例补的是删库路径的最后一步：只 deleteByQuery 会留下一个真实存在的空索引与别名，
        // 于是"库已删除"在检索侧变成"查得到但 0 条"（2026-10-19 的 E2E 里真留下过两个空索引）。
        // 它同时钉住一个实现细节：删除接口不接受通配表达式（destructive_requires_name 默认 true），
        // 必须 resolve 后按名字删——通配版本在真机上会直接抛 illegal_argument_exception。
        indexAndRefresh(List.of(
                chunk(61L, TEST_KB_ID, "标题", null, "这条内容会随索引一起消失。"),
                chunk(62L, OTHER_KB_ID, "标题", null, "别的库的内容应当留着。")));

        assertThat(aliasExists(TEST_KB_ID)).isTrue();
        assertThat(aliasExists(OTHER_KB_ID)).isTrue();
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isEqualTo(1);

        chunkIndex.deleteIndexFor(TEST_KB_ID);

        // 别名依附于物理索引，索引没了别名也没了
        assertThat(aliasExists(TEST_KB_ID)).as("别名应随索引消失").isFalse();
        assertThat(physicalIndexExists(TEST_KB_ID)).as("物理索引应被删除").isFalse();
        // 删了索引之后：count 是 0、search 是空，而不是 404/异常（ignoreUnavailable 的意义）
        assertThat(chunkIndex.countByKnowledgeBase(TEST_KB_ID)).isZero();
        assertThat(chunkIndex.search(TEST_KB_ID, "内容", 5)).isEmpty();

        // 幂等：再删一次不报错（删库可能重试）
        chunkIndex.deleteIndexFor(TEST_KB_ID);

        assertThat(aliasExists(OTHER_KB_ID)).as("别的库的索引不该受影响").isTrue();
        assertThat(chunkIndex.countByKnowledgeBase(OTHER_KB_ID)).isEqualTo(1);
        assertThat(chunkIndex.search(OTHER_KB_ID, "别的库", 5))
                .extracting(ChunkSearchHit::chunkId).contains(62L);
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

    /** 刷新本测试用到的两份索引（通配到物理索引；别名没有 refresh 的意义）。 */
    private void refresh() {
        try {
            client.indices().refresh(r -> r.index(alias + "_*").ignoreUnavailable(true));
        } catch (IOException ex) {
            throw new IllegalStateException("refresh 失败", ex);
        }
    }

    /** 别名当前指向的物理索引名（{@code _analyze} 需要一个具体索引）。 */
    private String physicalIndexOf(Long knowledgeBaseId) throws IOException {
        return client.indices().getAlias(g -> g.name(chunkIndex.aliasFor(knowledgeBaseId)))
                .aliases().keySet().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("知识库 " + knowledgeBaseId + " 没有索引"));
    }

    /** 该知识库的别名是否存在。 */
    private boolean aliasExists(Long knowledgeBaseId) throws IOException {
        return client.indices().existsAlias(e -> e.name(chunkIndex.aliasFor(knowledgeBaseId))).value();
    }

    /** 该知识库是否还有物理索引（通配只匹配 {@code <别名>_<时间戳>_<序号>} 形式的物理索引）。 */
    private boolean physicalIndexExists(Long knowledgeBaseId) throws IOException {
        return !client.indices().get(g -> g
                .index(chunkIndex.aliasFor(knowledgeBaseId) + "_*")
                .ignoreUnavailable(true)).indices().isEmpty();
    }

    /**
     * 删掉两个测试知识库的物理索引（删索引会一并删掉绑定在它上面的别名）。
     *
     * <p>{@code alias_*} 只匹配物理索引（{@code <alias>_<kbId>_<ts>_<seq>}），
     * 不会匹配到别名本身（{@code <alias>_<kbId>}）——少一层通配就少一次误伤。
     *
     * <p><b>先查出名字、再按名字删，不能用通配符直接删</b>：ES 的
     * {@code action.destructive_requires_name} 默认为 {@code true}，
     * 通配符直接进 delete 会被拒（{@code illegal_argument_exception:
     * Wildcard expressions or all indices are not allowed}）。
     * 早先这里写的正是通配符版本——它在本机 compose 那套 ES 上从来没跑通过，
     * 只是因为本测试默认被环境变量关着，一直没有暴露。
     * 改成"resolve 后精确删除"之后，两种配置下都成立。
     */
    private void dropIndexes() {
        try {
            for (Long knowledgeBaseId : List.of(TEST_KB_ID, OTHER_KB_ID)) {
                var found = client.indices().get(g -> g
                        .index(chunkIndex.aliasFor(knowledgeBaseId) + "_*")
                        .ignoreUnavailable(true));
                if (found.indices().isEmpty()) {
                    continue;
                }
                client.indices().delete(d -> d.index(List.copyOf(found.indices().keySet())));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("清理测试索引失败", ex);
        }
    }

    private IndexedChunk chunk(Long chunkId, Long kbId, String title, String headingPath, String content) {
        return chunkOfDocument(chunkId, kbId, chunkId, content, title, headingPath);
    }

    /** 指定 documentId 的切片（按文档删除的用例需要几个切片共享/区分文档 id）。 */
    private IndexedChunk chunkOfDocument(Long chunkId, Long kbId, Long documentId, String content) {
        return chunkOfDocument(chunkId, kbId, documentId, content, "标题", null);
    }

    private IndexedChunk chunkOfDocument(Long chunkId, Long kbId, Long documentId, String content,
                                         String title, String headingPath) {
        return new IndexedChunk(chunkId, kbId, documentId, 1L, title, headingPath, content,
                List.of("java"), "notes", 0);
    }
}
