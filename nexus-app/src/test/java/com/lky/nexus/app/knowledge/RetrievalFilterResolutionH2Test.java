package com.lky.nexus.app.knowledge;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFilterResolver;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFilterResolver.FilterPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过滤解析在真实 SQL 上的行为（H2，与 MySQL 语义一致的子集）。
 *
 * <p><b>为什么这条测试必须有</b>：过滤解析是"用数据库里的标签/目录换出一批文档 id"，
 * 而这里的坑全在 SQL 细节上——LIKE 的通配符转义、手写 SQL 漏掉逻辑删除、
 * 目录前缀少了分隔符把 {@code Javascript} 也匹配进 {@code Java}。
 * 这些都不会抛异常，只会多筛或少筛几篇，靠肉眼看代码很难发现。
 *
 * <p>用 {@code @Transactional} 让插入的数据自动回滚，避免污染其它用例共享的 H2 库。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("过滤解析：真实 SQL（标签 join + 目录 LIKE 转义 + 逻辑删除）")
class RetrievalFilterResolutionH2Test {

    private static final long KB_ID = 880_001L;
    private static final long OTHER_KB_ID = 880_002L;

    @Autowired
    private RetrievalFilterResolver resolver;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentTagMapper documentTagMapper;

    @Test
    @DisplayName("标签是 any-of：给两个标签，两批文档都命中（不是交集）")
    void tagsAreAnyOf() {
        long java = document("java.md", "Java");
        long spring = document("spring.md", "Spring");
        long redis = document("redis.md", "Redis");
        tag(java, "算法");
        tag(spring, "后端");
        tag(redis, "缓存");

        FilterPlan both = resolver.resolve(KB_ID,
                new RetrievalFilters(List.of("算法", "后端"), null, null));
        assertThat(both.sortedDocumentIds()).containsExactlyInAnyOrder(java, spring);

        FilterPlan single = resolver.resolve(KB_ID, new RetrievalFilters(List.of("缓存"), null, null));
        assertThat(single.sortedDocumentIds()).containsExactly(redis);
    }

    @Test
    @DisplayName("目录匹配含子目录，但不匹配同前缀的兄弟目录")
    void folderMatchesSubfoldersOnly() {
        long root = document("并发.md", "Java");
        long nested = document("锁.md", "Java/并发");
        long deeper = document("AQS.md", "Java/并发/AQS");
        long sibling = document("js.md", "Javascript");
        long outside = document("cache.md", "Redis");

        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(null, "Java", null));

        assertThat(plan.sortedDocumentIds())
                .as("Java 及其子目录；Javascript 只是前缀相同，不是子目录")
                .containsExactlyInAnyOrder(root, nested, deeper)
                .doesNotContain(sibling, outside);
    }

    @Test
    @DisplayName("目录名里的下划线是普通字符，不是 LIKE 通配符（day_01 不能匹配 day101）")
    void underscoreInFolderIsLiteral() {
        long day01 = document("day01.md", "day_01");
        document("day101.md", "day101");   // 若 _ 被当成通配符，这一篇会被误命中

        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(null, "day_01", null));

        assertThat(plan.sortedDocumentIds()).containsExactly(day01);
    }

    @Test
    @DisplayName("百分号同样是普通字符")
    void percentInFolderIsLiteral() {
        long percent = document("p.md", "100%");
        document("other.md", "100x");

        assertThat(resolver.resolve(KB_ID, new RetrievalFilters(null, "100%", null)).documentIds())
                .containsExactly(percent);
    }

    @Test
    @DisplayName("已删除的文档不参与过滤结果（手写 SQL 不会自动带上逻辑删除条件）")
    void softDeletedDocumentsAreExcluded() {
        long alive = document("alive.md", "算法");
        long deletedByFolder = document("deleted.md", "算法");
        long deletedByTag = document("deleted-tag.md", "算法");
        tag(deletedByTag, "动态规划");
        softDelete(deletedByFolder);
        softDelete(deletedByTag);

        assertThat(resolver.resolve(KB_ID, new RetrievalFilters(null, "算法", null)).documentIds())
                .as("MyBatis-Plus 的逻辑删除只作用于它生成的 SQL，手写的必须自己写 deleted = 0")
                .containsExactly(alive);
        assertThat(resolver.resolve(KB_ID, new RetrievalFilters(List.of("动态规划"), null, null)).documentIds())
                .as("标签表没有逻辑删除列，靠 join 文档表把已删除的挡掉")
                .isEmpty();
    }

    @Test
    @DisplayName("过滤条件不跨知识库：同样目录名在别的库里查不到")
    void filtersAreScopedToKnowledgeBase() {
        document("mine.md", "Java");
        insertDocument(OTHER_KB_ID, "theirs.md", "Java");

        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(null, "Java", null));

        assertThat(plan.documentIds()).hasSize(1);
    }

    @Test
    @DisplayName("标签谁都没有 → \"命中零篇\"（而不是\"不过滤\"），这是与全库检索的分界")
    void unmatchedTagMeansNothing() {
        document("java.md", "Java");

        FilterPlan plan = resolver.resolve(KB_ID,
                new RetrievalFilters(List.of("这个标签不存在"), null, null));

        assertThat(plan.matchNothing()).isTrue();

        FilterPlan noFilter = resolver.resolve(KB_ID, RetrievalFilters.none());
        assertThat(noFilter.matchNothing())
                .as("同一份数据，没有条件时必须是\"不过滤\"——两者行为必须不同")
                .isFalse();
    }

    @Test
    @DisplayName("标签 + 目录 + 指定文档：三个条件是 AND")
    void threeConditionsIntersect() {
        long a = document("a.md", "Java");
        long b = document("b.md", "Java");
        document("c.md", "Redis");
        tag(a, "算法");
        tag(b, "算法");

        FilterPlan plan = resolver.resolve(KB_ID,
                new RetrievalFilters(List.of("算法"), "Java", List.of(a)));

        assertThat(plan.sortedDocumentIds()).containsExactly(a);
    }

    // ------------------------------------------------------------------

    /** 建一篇文档，返回 id（目录与文件名同批次内唯一即可）。 */
    private long document(String fileName, String folder) {
        return insertDocument(KB_ID, fileName, folder);
    }

    private long insertDocument(long knowledgeBaseId, String fileName, String folder) {
        DocumentPO document = new DocumentPO();
        document.setKbId(knowledgeBaseId);
        document.setUserId(1L);
        document.setFileName(fileName);
        document.setFileExt("md");
        document.setSourceType("MANUAL");
        document.setSourcePath(folder + "/" + fileName);
        document.setFolder(folder);
        document.setTitle(fileName);
        document.setFileHash("hash-" + knowledgeBaseId + "-" + folder + "-" + fileName);
        document.setStatus("INDEXED");
        documentMapper.insert(document);
        return document.getId();
    }

    private void tag(long documentId, String tag) {
        DocumentTagPO entity = new DocumentTagPO();
        entity.setKbId(KB_ID);
        entity.setDocumentId(documentId);
        entity.setTag(tag);
        entity.setTagSource("FRONTMATTER");
        documentTagMapper.insert(entity);
    }

    private void softDelete(long documentId) {
        documentMapper.deleteById(documentId);
    }
}
