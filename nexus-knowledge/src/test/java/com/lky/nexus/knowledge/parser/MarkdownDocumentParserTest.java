package com.lky.nexus.knowledge.parser;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Markdown 解析器测试（纯单元测试，不启动 Spring）。
 *
 * <p>这是整个知识库最关键的测试：解析结果直接决定切分边界、标签过滤与引用定位。
 * 重点覆盖两类容易出错的地方——
 * <ol>
 *   <li><b>代码块必须跳过</b>：笔记里到处是 {@code # 注释}、{@code [[示例]]}、{@code #tag}，
 *       一旦被当成真结构，会污染切片与知识关联；</li>
 *   <li><b>偏移量必须准确</b>：它决定切分结果能否回溯到原文、引用能否定位到标题。</li>
 * </ol>
 */
class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    @DisplayName("解析 frontmatter：title / tags 提取，正文不含 frontmatter")
    void parsesFrontmatter() {
        String markdown = """
                ---
                title: Spring Boot
                tags:
                  - Java
                  - SpringBoot
                ---

                # Spring Boot

                ## 自动配置

                Spring Boot ...自动装配...
                """;

        ParsedDocument document = parser.parse("SpringBoot.md", markdown);

        assertThat(document.title()).isEqualTo("Spring Boot");
        assertThat(document.tags()).containsExactlyInAnyOrder("java", "springboot");
        assertThat(document.content()).startsWith("# Spring Boot");
        assertThat(document.content()).doesNotContain("title: Spring Boot");
        assertThat(document.frontmatter()).containsEntry("title", "Spring Boot");
    }

    @Test
    @DisplayName("标题：层级、文本、偏移量、锚点都正确；偏移量指向正文而不是原文")
    void parsesHeadingsWithOffsets() {
        String markdown = """
                ---
                title: 忽略我用这个标题
                ---
                # Redis

                缓存相关内容。

                ## 缓存击穿

                互斥锁方案。

                ### 互斥锁
                """;

        ParsedDocument document = parser.parse("redis.md", markdown);

        List<ParsedDocument.Heading> headings = document.headings();
        assertThat(headings).hasSize(3);
        assertThat(headings.get(0).level()).isEqualTo(1);
        assertThat(headings.get(0).text()).isEqualTo("Redis");
        assertThat(headings.get(0).anchor()).isEqualTo("redis");
        assertThat(headings.get(1).text()).isEqualTo("缓存击穿");
        assertThat(headings.get(2).level()).isEqualTo(3);

        // 偏移量必须落在 content 上，且能按偏移切回标题原文
        ParsedDocument.Heading second = headings.get(1);
        assertThat(document.content().substring(second.startOffset()))
                .startsWith("## 缓存击穿");
        assertThat(headings.get(2).startOffset()).isGreaterThan(second.startOffset());
    }

    @Test
    @DisplayName("代码块内的标题/标签/链接一律不算结构（Markdown 解析最典型的坑）")
    void skipsFencedCodeBlocks() {
        String markdown = """
                # 真标题

                ```java
                // # 这是注释不是标题
                ## 也不是标题
                String s = "[[不是链接]] #不是标签";
                ```

                ```
                # 同样不是标题
                ```

                # 又一个真标题
                """;

        ParsedDocument document = parser.parse("code.md", markdown);

        assertThat(document.headings()).extracting(ParsedDocument.Heading::text)
                .containsExactly("真标题", "又一个真标题");
        assertThat(document.tags()).isEmpty();
        assertThat(document.links()).isEmpty();
    }

    @Test
    @DisplayName("上下文代码span里的 # 与 [[ ]] 也要跳过")
    void skipsInlineCode() {
        String markdown = """
                # 标题

                行内代码 `#不是标签` 与 `[[不是链接]]` 都应被忽略。
                """;

        ParsedDocument document = parser.parse("inline.md", markdown);

        assertThat(document.tags()).isEmpty();
        assertThat(document.links()).isEmpty();
    }

    @Test
    @DisplayName("WikiLink：[[目标]] / [[目标|别名]] / [[目标#小节]]")
    void parsesWikiLinks() {
        String markdown = """
                # 索引

                参考 [[SpringBoot]]、[[Redis缓存|缓存笔记]] 与 [[JVM#垃圾回收]]。
                """;

        ParsedDocument document = parser.parse("index.md", markdown);

        assertThat(document.links()).hasSize(3);
        assertThat(document.links()).extracting(ParsedDocument.Link::target)
                .containsExactly("SpringBoot", "Redis缓存", "JVM#垃圾回收");
        assertThat(document.links()).allMatch(link -> link.type() == ParsedDocument.LinkType.WIKILINK);
        assertThat(document.links().get(1).display()).isEqualTo("缓存笔记");
    }

    @Test
    @DisplayName("Markdown 链接与图片：链接进 links，图片进 attachments")
    void parsesMarkdownLinksAndImages() {
        String markdown = """
                # 文档

                见 [官方文档](https://spring.io/projects/spring-boot)。

                ![架构图](Attachments/arch.png)

                ![[Attachments/时序图.png]]
                """;

        ParsedDocument document = parser.parse("doc.md", markdown);

        assertThat(document.links()).extracting(ParsedDocument.Link::target)
                .containsExactly("https://spring.io/projects/spring-boot");
        assertThat(document.attachments()).extracting(ParsedDocument.Attachment::path)
                .containsExactlyInAnyOrder("Attachments/arch.png", "Attachments/时序图.png");
    }

    @Test
    @DisplayName("Obsidian 非图片嵌入 ![[另一篇笔记]] 记为 EMBED 链接")
    void parsesNonImageEmbedAsLink() {
        String markdown = """
                # 汇总

                ![[SpringBoot笔记]]
                """;

        ParsedDocument document = parser.parse("summary.md", markdown);

        assertThat(document.links()).hasSize(1);
        assertThat(document.links().get(0).type()).isEqualTo(ParsedDocument.LinkType.EMBED);
        assertThat(document.links().get(0).target()).isEqualTo("SpringBoot笔记");
        assertThat(document.attachments()).isEmpty();
    }

    @Test
    @DisplayName("内联标签：#Java #spring-boot，与 frontmatter 标签合并去重并统一小写")
    void mergesInlineAndFrontmatterTags() {
        String markdown = """
                ---
                tags: [Java, redis]
                ---
                # 笔记

                今天复习了 #Java 与 #spring-boot，还有 #Redis/缓存。
                """;

        ParsedDocument document = parser.parse("tags.md", markdown);

        assertThat(document.tags()).containsExactlyInAnyOrder("java", "redis", "spring-boot", "redis/缓存");
    }

    @Test
    @DisplayName("无 frontmatter、无标题：标题回退为文件名")
    void fallsBackToFileNameAsTitle() {
        ParsedDocument document = parser.parse("纯文本笔记.md", "没有任何结构的一段话。");

        assertThat(document.title()).isEqualTo("纯文本笔记");
        assertThat(document.headings()).isEmpty();
        assertThat(document.hasHeadings()).isFalse();
        assertThat(document.content()).isEqualTo("没有任何结构的一段话。");
    }

    @Test
    @DisplayName("只有开头 --- 没有结束符：当成普通正文，不报错")
    void toleratesUnclosedFrontmatter() {
        ParsedDocument document = parser.parse("broken.md", """
                ---
                title: 没有结束分隔符

                正文继续
                """);

        assertThat(document.frontmatter()).isEmpty();
        assertThat(document.content()).startsWith("---");
    }

    @Test
    @DisplayName("frontmatter YAML 语法错误 → 3004（可定位的错误，而不是静默丢弃）")
    void reportsMalformedFrontmatter() {
        String markdown = """
                ---
                title: "引号没有闭合
                ---
                正文
                """;

        assertThatThrownBy(() -> parser.parse("bad.md", markdown))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_PARSE_FAILED.getCode());
    }

    @Test
    @DisplayName("CRLF 归一化：Windows 换行不影响标题与偏移量")
    void normalizesWindowsLineEndings() {
        String markdown = "# 标题一\r\n\r\n正文。\r\n\r\n## 标题二\r\n";

        ParsedDocument document = parser.parse("crlf.md", markdown);

        assertThat(document.content()).doesNotContain("\r");
        assertThat(document.headings()).extracting(ParsedDocument.Heading::text)
                .containsExactly("标题一", "标题二");
        assertThat(document.content().substring(document.headings().get(1).startOffset()))
                .startsWith("## 标题二");
    }
}
