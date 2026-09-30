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

/**
 * Elasticsearch 检索索引适配器（BM25）。
 *
 * <p><b>IK 分词的两个分析器</b>：索引期用 {@code ik_max_word}（最细粒度切分 → 召回高），
 * 检索期用 {@code ik_smart}（粗粒度 → 精确度高）。这是 IK 的推荐用法：
 * 若两边都用 max_word，查询词被切得太碎，反而会命中大量无关内容。
 *
 * <p><b>别名切换</b>：物理索引名带版本/时间戳，对外只暴露别名。
 * 重建索引时新建物理索引 → 一次性原子切换别名 → 删除旧的。
 * 直接删表重建会让检索出现"空窗期"，而别名切换对查询方是无感的。
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

    private final ElasticsearchClient client;
    private final String alias;
    private final String indexAnalyzer;
    private final String searchAnalyzer;

    public ElasticsearchChunkIndex(ElasticsearchClient client,
                                   @Value("${nexus.search.index-alias:nexus_chunk}") String alias,
                                   @Value("${nexus.search.analyzer.index:ik_max_word}") String indexAnalyzer,
                                   @Value("${nexus.search.analyzer.search:ik_smart}") String searchAnalyzer) {
        this.client = client;
        this.alias = alias;
        this.indexAnalyzer = indexAnalyzer;
        this.searchAnalyzer = searchAnalyzer;
    }

    @Override
    public void ensureIndex() {
        try {
            boolean aliasExists = client.indices().existsAlias(e -> e.name(alias)).value();
            if (aliasExists) {
                return;
            }
            String physical = alias + "_" + LocalDateTime.now().format(INDEX_SUFFIX);
            createIndex(physical);
            client.indices().putAlias(p -> p.index(physical).name(alias));
            log.info("已创建检索索引 {} 并绑定别名 {}", physical, alias);
        } catch (IOException ex) {
            throw new IllegalStateException("初始化 ES 索引失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public void recreateIndex() {
        try {
            String newIndex = alias + "_" + LocalDateTime.now().format(INDEX_SUFFIX);
            createIndex(newIndex);
            String oldIndex = currentIndexBehindAlias();

            if (oldIndex == null) {
                client.indices().putAlias(p -> p.index(newIndex).name(alias));
            } else {
                // updateAliases 是原子的：查询方不会看到"没有别名"的瞬间。
                // 注意 Action 需要用 of(...) 包一层（接口里是 Action 容器类型，不是直接的 builder）
                client.indices().updateAliases(u -> u.actions(
                        Action.of(a -> a.remove(r -> r.index(oldIndex).alias(alias))),
                        Action.of(a -> a.add(ad -> ad.index(newIndex).alias(alias)))));
            }
            log.info("检索索引已重建：{} → {}（别名 {}）", oldIndex, newIndex, alias);

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
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder();
            for (IndexedChunk chunk : chunks) {
                bulk.operations(op -> op.index(idx -> idx
                        .index(alias)
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
            log.debug("写入 ES {} 条（索引 {}）", chunks.size(), alias);
        } catch (IOException ex) {
            throw new IllegalStateException("ES 批量写入失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<ChunkSearchHit> search(Long knowledgeBaseId, String query, int topK,
                                       RetrievalFilters filters) {
        try {
            SearchResponse<Void> response = client.search(s -> s
                    .index(alias)
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
        deleteByQuery("document_id", documentIds.stream()
                .map(id -> FieldValue.of(id.longValue())).toList());
    }

    @Override
    public void deleteByKnowledgeBase(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        deleteByQuery("knowledge_base_id", List.of(FieldValue.of(knowledgeBaseId.longValue())));
    }

    @Override
    public long countByKnowledgeBase(Long knowledgeBaseId) {
        try {
            return client.count(c -> c
                    .index(alias)
                    .query(q -> q.term(t -> t
                            .field("knowledge_base_id")
                            .value(FieldValue.of(knowledgeBaseId.longValue()))))).count();
        } catch (IOException ex) {
            throw new IllegalStateException("ES 统计失败：" + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------

    private void deleteByQuery(String field, List<FieldValue> values) {
        try {
            var response = client.deleteByQuery(d -> d
                    .index(alias)
                    .query(q -> q.terms(t -> t.field(field).terms(tt -> tt.value(values)))));
            log.debug("ES 按 {} 删除 {} 条", field, response.deleted());
        } catch (IOException ex) {
            throw new IllegalStateException("ES 删除失败：" + ex.getMessage(), ex);
        }
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

    /** 解析别名当前指向的物理索引（没有别名时返回 null）。 */
    private String currentIndexBehindAlias() throws IOException {
        GetAliasResponse response = client.indices().getAlias(g -> g.name(alias));
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
