package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.rag.retriever.RetrievalFilterResolver.FilterPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 过滤条件解析（标签/目录 → 文档 id）的行为测试。
 *
 * <p>核心要钉住的是一条容易被"优化"掉的语义：<b>"解析出零篇" ≠ "没有条件"</b>。
 * 前者必须让检索返回空，后者才是不过滤。混淆这两者的表现是"按标签筛了还是全库结果"，
 * 而且不报错——只有测试能防住。
 */
@DisplayName("过滤器解析：标签/目录 → 文档 id")
class RetrievalFilterResolverTest {

    private static final long KB_ID = 77L;

    private DocumentTagMapper documentTagMapper;
    private DocumentMapper documentMapper;
    private RetrievalFilterResolver resolver;

    @BeforeEach
    void setUp() {
        documentTagMapper = mock(DocumentTagMapper.class);
        documentMapper = mock(DocumentMapper.class);
        resolver = new RetrievalFilterResolver(documentTagMapper, documentMapper);
    }

    @Test
    @DisplayName("没有条件：一次数据库都不查")
    void noFiltersTouchesNothing() {
        FilterPlan plan = resolver.resolve(KB_ID, RetrievalFilters.none());

        assertThat(plan.matchNothing()).isFalse();
        assertThat(plan.hasDocumentScope()).isFalse();
        verifyNoInteractions(documentTagMapper, documentMapper);
    }

    @Test
    @DisplayName("只给标签：查标签表，不查目录")
    void tagsOnly() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("算法"))).thenReturn(List.of(1L, 2L));

        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(List.of("算法"), null, null));

        assertThat(plan.sortedDocumentIds()).containsExactly(1L, 2L);
        verify(documentTagMapper).selectDocumentIdsByTags(KB_ID, List.of("算法"));
        verify(documentMapper, never()).selectIdsByFolder(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("只给目录：前缀带上分隔符，让匹配范围含子目录")
    void folderOnly() {
        when(documentMapper.selectIdsByFolder(KB_ID, "算法", "算法/%")).thenReturn(List.of(3L));

        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(null, "算法", null));

        assertThat(plan.sortedDocumentIds()).containsExactly(3L);
        verify(documentMapper).selectIdsByFolder(KB_ID, "算法", "算法/%");
        verify(documentTagMapper, never()).selectDocumentIdsByTags(anyLong(), anyList());
    }

    @Test
    @DisplayName("标签 + 目录：两个条件是 AND，取交集")
    void tagsAndFolderIntersect() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("算法"))).thenReturn(List.of(1L, 2L));
        when(documentMapper.selectIdsByFolder(KB_ID, "算法", "算法/%")).thenReturn(List.of(2L, 3L));

        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(List.of("算法"), "算法", null));

        assertThat(plan.matchNothing()).isFalse();
        assertThat(plan.sortedDocumentIds()).as("交集，而不是并集").containsExactly(2L);
    }

    @Test
    @DisplayName("显式指定的文档也要参与求交集：'这两篇里带某标签的'")
    void explicitDocumentIdsIntersectWithTags() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("算法"))).thenReturn(List.of(1L, 2L));

        FilterPlan plan = resolver.resolve(KB_ID,
                new RetrievalFilters(List.of("算法"), null, List.of(2L, 9L)));

        assertThat(plan.sortedDocumentIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("显式指定文档时不需要查库")
    void explicitDocumentIdsNeedNoQuery() {
        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(null, null, List.of(5L)));

        assertThat(plan.sortedDocumentIds()).containsExactly(5L);
        verifyNoInteractions(documentTagMapper, documentMapper);
    }

    @Test
    @DisplayName("标签谁都没有 → 明确\"命中零篇\"，绝不退化成\"不过滤\"")
    void tagWithoutMatchMeansNothing() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("不存在的标签"))).thenReturn(List.of());

        FilterPlan plan = resolver.resolve(KB_ID,
                new RetrievalFilters(List.of("不存在的标签"), null, null));

        assertThat(plan.matchNothing())
                .as("这是本类最关键的一条语义：筛不到东西必须返回空，而不是把条件丢掉")
                .isTrue();
        assertThat(plan.hasDocumentScope()).isFalse();
        assertThat(plan.describe()).contains("命中 0 篇");
    }

    @Test
    @DisplayName("交集为空（标签有、目录也有，但没有一篇同时满足）同样是\"命中零篇\"")
    void emptyIntersectionMeansNothing() {
        when(documentTagMapper.selectDocumentIdsByTags(KB_ID, List.of("算法"))).thenReturn(List.of(1L));
        when(documentMapper.selectIdsByFolder(KB_ID, "算法", "算法/%")).thenReturn(List.of(2L));

        FilterPlan plan = resolver.resolve(KB_ID, new RetrievalFilters(List.of("算法"), "算法", null));

        assertThat(plan.matchNothing()).isTrue();
    }

    @Test
    @DisplayName("目录名里的 _ 与 % 必须转义：day_01 不能匹配到 day101")
    void likePrefixEscapesWildcards() {
        assertThat(RetrievalFilterResolver.likePrefix("day_01")).isEqualTo("day!_01/%");
        assertThat(RetrievalFilterResolver.likePrefix("100%")).isEqualTo("100!%/%");
        assertThat(RetrievalFilterResolver.likePrefix("a!b")).isEqualTo("a!!b/%");
        assertThat(RetrievalFilterResolver.likePrefix("算法")).isEqualTo("算法/%");
    }

    @Test
    @DisplayName("describe() 让日志里能看出这次到底按什么条件筛的")
    void describeIsReadable() {
        assertThat(RetrievalFilters.none().describe()).isEqualTo("无");
        assertThat(new RetrievalFilters(List.of("算法", "Java"), "算法", List.of(1L, 2L)).describe())
                .isEqualTo("tags=[算法, Java] folder=算法 docs=2 篇");
        assertThat(FilterPlan.limited(List.of(1L, 2L, 3L)).describe()).isEqualTo("限定 3 篇");
        assertThat(FilterPlan.none().describe()).isEqualTo("不限文档");
    }
}
