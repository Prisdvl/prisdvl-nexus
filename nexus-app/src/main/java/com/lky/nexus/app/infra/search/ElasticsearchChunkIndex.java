package com.lky.nexus.app.infra.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.search.IndexedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Elasticsearch 检索索引适配器（BM25）。
 *
 * <p><b>IK 分词的两个分析器</b>：索引期用 {@code ik_max_word}（最细粒度切分 → 召回高），
 * 检索期用 {@code ik_smart}（粗粒度 → 精确度高）。这是 IK 的推荐用法：
 * 若两边都用 max_word，查询词被切得太碎，反而会命中大量无关内容。
 *
 * <p><b>每知识库一份物理索引 + 一个别名</b>（{@code <base-alias>_<knowledgeBaseId>}）。
 * 这不是为了"看起来更规范"，而是修一个真实的数据一致性缺陷：
 * 早先所有知识库共用一份物理索引，重建 KB-A 会新建并切换<b>全局</b>别名，
 * 于是 B/C 的 ES 文档被连带清空，而 B/C 的切片在 MySQL 里仍是 {@code DONE}、
 * 不会被重新索引——<b>B/C 的 BM25 检索静默失效</b>，且因为向量那一路仍然召回，
 * 症状只会表现为"少了一路召回"，几乎不可能被发现。
 * 索引粒度与"谁会被重建"对齐之后，重建 A 影响不到 B 是结构性的，不靠调用方补偿。
 *
 * <p><b>物理索引名带秒级时间戳 + 单调序号</b>：只用 {@code yyyyMMddHHmmss} 时，
 * 同一秒内连续重建两次会撞 {@code resource_already_exists_exception} 并返回 500
 * （更糟的是当时切片已被标成已索引，索引其实是空的）。序号让同秒重建也必然拿到新名字。
 *
 * <p><b>字段设计</b>：{@code title^3 / heading_path^2 / content^1}——
 * 标题命中比正文命中更有价值，这种权重是 BM25 相对向量检索的重要补充。
 * 标签与目录用 {@code keyword}（要精确过滤，不能被分词）。
 */
@Repository
@ConditionalOnProperty(name = "nexus.search.enabled", havingValue = "true")
public class ElasticsearchChunkIndex implements ChunkIndex {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchChunkIndex.class);

    private static final DateTimeFormatter INDEX_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 同秒重名的兜底重试上限。正常一次就够，这个上限只用来杜绝死循环。 */
    private static final int MAX_NAME_ATTEMPTS = 1000;

    private final ElasticsearchClient client;
    private final String alias;
    private final String indexAnalyzer;
    private final String searchAnalyzer;

    /**
     * 物理索引名的单调序号（进程内）。
     *
     * <p>时间戳只到秒，同秒第二次重建就会算出同一个名字。序号让同一秒内的每次重建
     * 都得到不同的名字；跨进程重启时若极端巧合仍撞名，{@link #nextPhysicalIndexName}
     * 会带着更大的序号重试，而不是抛异常。
     */
    private final AtomicLong indexSequence = new AtomicLong();

    public ElasticsearchChunkIndex(ElasticsearchClient client,
                                   @Value("${nexus.search.index-alias:nexus_chunk}") String alias,
                                   @Value("${nexus.search.analyzer.index:ik_max_word}") String indexAnalyzer,
                                   @Value("${nexus.search.analyzer.search:ik_smart}") String searchAnalyzer) {
        this.client = client;
        this.alias = alias;
        this.indexAnalyzer = indexAnalyzer;
        this.searchAnalyzer = searchAnalyzer;
    }

    /**
     * 某知识库的别名。对外（写入/检索/重建）一律只出现别名，物理索引名是实现细节。
     */
    public String aliasFor(Long knowledgeBaseId) {
        return alias + "_" + knowledgeBaseId;
    }

    /**
     * 物理索引名 = 知识库别名 + 秒级时间戳 + 序号。
     *
     * <p>抽成 <b>静态纯函数</b>是为了能脱离 Elasticsearch 断言"同秒也必然唯一"这件事——
     * 否则这条修复只能靠"读代码"验证，而它恰恰是之前真实踩过的坑。
     */
    static String physicalIndexName(String knowledgeBaseAlias, LocalDateTime now, long sequence) {
        return knowledgeBaseAlias + "_" + now.format(INDEX_SUFFIX) + "_" + sequence;
    }

    @Override
    public void ensureIndex(Long knowledgeBaseId) {
        try {
            String knowledgeBaseAlias = aliasFor(knowledgeBaseId);
            boolean aliasExists = client.indices().existsAlias(e -> e.name(knowledgeBaseAlias)).value();
            if (aliasExists) {
                return;
            }
            String physical = nextPhysicalIndexName(knowledgeBaseAlias);
            createIndex(physical);
            client.indices().putAlias(p -> p.index(physical).name(knowledgeBaseAlias));
            log.info("已为知识库 {} 创建检索索引 {} 并绑定别名 {}",
                    knowledgeBaseId, physical, knowledgeBaseAlias);
        } catch (IOException ex) {
            throw new IllegalStateException("初始化 ES 索引失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public void recreateIndex(Long knowledgeBaseId) {
        try {
            String knowledgeBaseAlias = aliasFor(knowledgeBaseId);
            String newIndex = nextPhysicalIndexName(knowledgeBaseAlias);
            createIndex(newIndex);
            String oldIndex = currentIndexBehindAlias(knowledgeBaseAlias);

            if (oldIndex == null) {
                client.indices().putAlias(p -> p.index(newIndex).name(knowledgeBaseAlias));
            } else {
                // updateAliases 是原子的：查询方不会看到"没有别名"的瞬间。
                // 注意 Action 需要用 of(...) 包一层（接口里是 Action 容器类型，不是直接的 builder）
                client.indices().updateAliases(u -> u.actions(
                        Action.of(a -> a.remove(r -> r.index(oldIndex).alias(knowledgeBaseAlias))),
                        Action.of(a -> a.add(ad -> ad.index(newIndex).alias(knowledgeBaseAlias)))));
            }
            log.info("知识库 {} 的检索索引已重建：{} → {}（别名 {}）",
                    knowledgeBaseId, oldIndex, newIndex, knowledgeBaseAlias);

            // 旧索引不再被引用，删掉避免占空间；失败不影响本次重建结果
            if (oldIndex != null && !oldIndex.equals(newIndex)) {
                try {
                    client.indices().delete(d -> d.index(oldIndex));
                } catch (IOException ex) {
                    log.warn("删除旧索引 {} 失败（忽略）：{}", oldIndex, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("重建 ES 索引失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public void indexChunks(List<IndexedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        // 一批切片理论上可以来自多个知识库（虽然当前调用方都是单库），
        // 因此按库分组后各写各的别名——否则"写进哪份索引"会变成不确定的。
        Map<Long, List<IndexedChunk>> byKnowledgeBase = new LinkedHashMap<>();
        for (IndexedChunk chunk : chunks) {
            byKnowledgeBase.computeIfAbsent(chunk.knowledgeBaseId(), k -> new ArrayList<>()).add(chunk);
        }
        for (Map.Entry<Long, List<IndexedChunk>> entry : byKnowledgeBase.entrySet()) {
            Long knowledgeBaseId = entry.getKey();
            // 别名不存在时 bulk 会整批失败，这里先确保（幂等，已存在时只花一次 existsAlias）
            ensureIndex(knowledgeBaseId);
            writeBatch(aliasFor(knowledgeBaseId), entry.getValue());
        }
    }

    private void writeBatch(String knowledgeBaseAlias, List<IndexedChunk> chunks) {
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder();
            for (IndexedChunk chunk : chunks) {
                bulk.operations(op -> op.index(idx -> idx
                        .index(knowledgeBaseAlias)
                        .id(String.valueOf(chunk.chunkId()))
                        .document(toDocument(chunk))));
            }
            BulkResponse response = client.bulk(bulk.build());
            if (response.errors()) {
                // 逐条收集失败原因：批量写入里"部分失败"很常见（映射冲突、字段超长），
                // 只报一个总数等于没报
                List<String> failures = new ArrayList<>();
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        failures.add("%s: %s".formatted(item.id(), item.error().reason()));
                    }
                }
                throw new IllegalStateException("ES 批量写入存在失败项：" + failures);
            }
            log.debug("写入 ES {} 条（索引 {}）", chunks.size(), knowledgeBaseAlias);
        } catch (IOException ex) {
            throw new IllegalStateException("ES 批量写入失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<ChunkSearchHit> search(Long knowledgeBaseId, String query, int topK,
                                       RetrievalFilters filters) {
        try {
            SearchResponse<Void> response = client.search(s -> s
                    .index(aliasFor(knowledgeBaseId))
                    // 该库从未建过索引时别名不存在。旧实现（全局唯一别名）此时返回的是"0 条命中"，
                    // 因此这里必须同样返回空，而不是抛 index_not_found——
                    // 否则"零知识库的新用户提问"会从"检索为空"变成"检索报错"。
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .size(topK)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.multiMatch(mm -> mm
                                    .query(query)
                                    // 标题权重最高：标题命中通常意味着整篇都相关
                                    .fields("title^3", "heading_path^2", "content^1")
                                    .type(TextQueryType.BestFields)))
                            // 过滤放在 filter 里：不参与打分，还能吃到 filter cache。
                            // 知识库条件始终在，其余按用户给的条件追加
                            .filter(kb -> kb.term(t -> t
                                    .field("knowledge_base_id")
                                    .value(FieldValue.of(knowledgeBaseId.longValue()))))
                            .filter(metadataClauses(filters)))), Void.class);

            return response.hits().hits().stream()
                    .map(hit -> new ChunkSearchHit(Long.valueOf(hit.id()),
                            hit.score() == null ? 0.0 : hit.score(), "bm25"))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("ES 检索失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 元数据过滤子句（标签 / 目录 / 指定文档）。
     *
     * <p>抽成独立的 <b>package-private 静态方法</b>是为了能在这台机器上真正验证它：
     * 本地没有 Elasticsearch（Docker/WSL2 不可用），检索本身只能标 UNAVAILABLE，
     * 但"过滤条件有没有被拼进查询、拼成了什么"是纯字符串/结构问题，可以脱离 ES 断言。
     * 否则整个过滤下推就只有"读代码"这一种验证方式。
     *
     * <p>三个条件的形态：
     * <ul>
     *   <li><b>标签</b>：{@code terms}（any-of）。{@code tags} 映射为 keyword，
     *       天然支持精确多值匹配，不需要 nested（我们只关心"有没有这个标签"，
     *       不关心标签在数组中的位置）；</li>
     *   <li><b>目录</b>：{@code should(folder = X, folder prefix "X/")}——点目录树上的节点
     *       应当包含子目录；{@code minimum_should_match=1} 让这个 should 变成硬条件
     *       （bool 里只有 should 时默认就是"至少一个"，但显式写出来才不会在
     *       以后有人往里加 must 时被静默降级成"可选"）；</li>
     *   <li><b>指定文档</b>：{@code terms} on document_id。</li>
     * </ul>
     *
     * <p>没有任何条件时返回 {@code match_all}：ES 的 {@code filter} 需要一个子句，
     * 传 null 会 NPE，而 {@code match_all} 在 filter 上下文里是零开销的（会被直接优化掉）。
     */
    static Query metadataClauses(RetrievalFilters filters) {
        if (filters == null || filters.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        BoolQuery.Builder bool = new BoolQuery.Builder();
        if (filters.hasTags()) {
            List<FieldValue> tags = filters.tags().stream().map(FieldValue::of).toList();
            bool.filter(f -> f.terms(t -> t.field("tags").terms(tt -> tt.value(tags))));
        }
        if (filters.hasFolder()) {
            String folder = filters.folder();
            bool.filter(f -> f.bool(b -> b
                    .should(s -> s.term(t -> t.field("folder").value(FieldValue.of(folder))))
                    // 子目录：文件夹路径用 "/" 分隔（见 vault 导入的相对路径），
                    // 因此 "算法" 的子目录形如 "算法/动态规划"
                    .should(s -> s.prefix(p -> p.field("folder").value(folder + "/")))
                    .minimumShouldMatch("1")));
        }
        if (filters.hasDocumentIds()) {
            // 注意 longValue()：FieldValue 既有 of(long) 也有 of(Object)，
            // 对**装箱**的 Long 来说，of(Object) 更具体因此会被选中，产出一个 "Any" 变体
            // （序列化仍能工作，但类型校验/断言都拿不到 long 变体）。显式取基本类型才是 of(long)。
            List<FieldValue> documentIds = filters.documentIds().stream()
                    .map(id -> FieldValue.of(id.longValue())).toList();
            bool.filter(f -> f.terms(t -> t.field("document_id").terms(tt -> tt.value(documentIds))));
        }
        return Query.of(q -> q.bool(bool.build()));
    }

    @Override
    public void deleteByDocumentIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        // longValue()：同样是避开 FieldValue.of(Object) 对装箱 Long 的更具体匹配（见 metadataClauses）
        // 索引按知识库分片，而这里只知道文档 id，因此用通配跨库删除。
        // ES 的索引解析结果是一个去重后的物理索引集合（别名会被展开成它指向的索引），
        // 所以模式同时匹配到别名与物理索引也不会重复删除。
        deleteByQuery(alias + "_*", "document_id", documentIds.stream()
                .map(id -> FieldValue.of(id.longValue())).toList());
    }

    @Override
    public void deleteByKnowledgeBase(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        deleteByQuery(aliasFor(knowledgeBaseId), "knowledge_base_id",
                List.of(FieldValue.of(knowledgeBaseId.longValue())));
    }

    @Override
    public long countByKnowledgeBase(Long knowledgeBaseId) {
        try {
            return client.count(c -> c
                    .index(aliasFor(knowledgeBaseId))
                    // 没建过索引的库计数为 0，而不是抛错（与 search 的理由相同）
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .query(q -> q.term(t -> t
                            .field("knowledge_base_id")
                            .value(FieldValue.of(knowledgeBaseId.longValue()))))).count();
        } catch (IOException ex) {
            throw new IllegalStateException("ES 统计失败：" + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------

    private void deleteByQuery(String index, String field, List<FieldValue> values) {
        try {
            var response = client.deleteByQuery(d -> d
                    .index(index)
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .query(q -> q.terms(t -> t.field(field).terms(tt -> tt.value(values)))));
            log.debug("ES 在 {} 上按 {} 删除 {} 条", index, field, response.deleted());
        } catch (IOException ex) {
            throw new IllegalStateException("ES 删除失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 取一个当前不存在的物理索引名。
     *
     * <p>时间戳只到秒，同秒第二次重建会算出同一个名字。序号解决绝大多数情况；
     * 这里的 exists 检查再兜住"进程重启后序号归零、而同一秒内恰好已有同名索引"的极端情况。
     */
    private String nextPhysicalIndexName(String knowledgeBaseAlias) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            String candidate = physicalIndexName(knowledgeBaseAlias, now, indexSequence.getAndIncrement());
            if (!client.indices().exists(e -> e.index(candidate)).value()) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法为 " + knowledgeBaseAlias + " 生成唯一的索引名（尝试 "
                + MAX_NAME_ATTEMPTS + " 次仍冲突）");
    }

    /** 建索引：映射里直接指定 IK 分析器，避免"写入能成功但检索不出中文"的经典问题。 */
    private void createIndex(String physicalIndex) throws IOException {
        client.indices().create(c -> c
                .index(physicalIndex)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")   // 单机开发环境：副本只会浪费空间与内存
                        .analysis(a -> a
                                .analyzer("nexus_index_analyzer",
                                        an -> an.custom(ca -> ca.tokenizer(indexAnalyzer)))
                                .analyzer("nexus_search_analyzer",
                                        an -> an.custom(ca -> ca.tokenizer(searchAnalyzer)))))
                .mappings(m -> m
                        .properties("chunk_id", p -> p.long_(l -> l))
                        .properties("knowledge_base_id", p -> p.long_(l -> l))
                        .properties("document_id", p -> p.long_(l -> l))
                        .properties("user_id", p -> p.long_(l -> l))
                        .properties("chunk_index", p -> p.integer(i -> i))
                        .properties("title", p -> p.text(t -> t
                                .analyzer("nexus_index_analyzer")
                                .searchAnalyzer("nexus_search_analyzer")))
                        .properties("heading_path", p -> p.text(t -> t
                                .analyzer("nexus_index_analyzer")
                                .searchAnalyzer("nexus_search_analyzer")))
                        .properties("content", p -> p.text(t -> t
                                .analyzer("nexus_index_analyzer")
                                .searchAnalyzer("nexus_search_analyzer")))
                        .properties("tags", p -> p.keyword(k -> k))
                        .properties("folder", p -> p.keyword(k -> k))));
    }

    /**
     * 解析别名当前指向的物理索引（没有别名时返回 null）。
     *
     * <p><b>必须先 {@code existsAlias} 再 {@code getAlias}</b>：别名不存在时
     * {@code getAlias} 抛的是 {@code ElasticsearchException[404 alias missing]}——
     * 它既不是"空响应"，也不是 {@link IOException}，所以既让 {@link #recreateIndex}
     * 里那个 {@code oldIndex == null} 的分支永远走不到，又绕过了那里的
     * {@code catch (IOException)}，一路冒到全局异常处理器变成 500。
     *
     * <p>这条路径在"全局唯一别名"时代碰不到（别名只要被写过一次就一直在，
     * 而重建总是发生在写过之后）。Phase 5.9 改成每知识库一个别名后，
     * "某个库还没有索引"成了常态——新建的空库、以及升级前入库的所有库都是这个状态，
     * 于是 {@code /index/recreate} 对这些库一律 500。2026-10-03 真机跑出来的，见 decisions.md B.51。
     */
    private String currentIndexBehindAlias(String knowledgeBaseAlias) throws IOException {
        if (!client.indices().existsAlias(e -> e.name(knowledgeBaseAlias)).value()) {
            return null;
        }
        GetAliasResponse response = client.indices().getAlias(g -> g.name(knowledgeBaseAlias));
        // ES 9 的 GetAliasResponse 直接暴露 aliases()（8.x 是 result()）
        return response.aliases().keySet().stream().findFirst().orElse(null);
    }

    private Map<String, Object> toDocument(IndexedChunk chunk) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("chunk_id", chunk.chunkId());
        document.put("knowledge_base_id", chunk.knowledgeBaseId());
        document.put("document_id", chunk.documentId());
        document.put("user_id", chunk.userId());
        document.put("chunk_index", chunk.chunkIndex());
        document.put("title", chunk.title());
        document.put("heading_path", chunk.headingPath());
        document.put("content", chunk.content());
        document.put("tags", chunk.tags() == null ? List.of() : chunk.tags());
        document.put("folder", chunk.folder());
        return document;
    }
}
