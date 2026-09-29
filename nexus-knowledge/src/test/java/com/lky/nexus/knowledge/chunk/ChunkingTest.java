package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.knowledge.model.ChunkStrategy;
import com.lky.nexus.knowledge.model.ChunkType;
import com.lky.nexus.knowledge.parser.MarkdownDocumentParser;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 2.4 验收测试：结构化切分。
 *
 * <p>切分是 RAG 效果的源头：切错了，后面的嵌入、检索、重排再优秀也救不回来。
 * 因此这里覆盖的都是"切错会真实伤害效果"的场景——
 * 代码块被腰斩、短小节检索不到、切片脱离文档后无法自解释。
 */
class ChunkingTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();
    private final TextSegmenter segmenter = new TextSegmenter();
    private final MarkdownHeadingChunker headingChunker = new MarkdownHeadingChunker(segmenter);
    private final RecursiveTextChunker recursiveChunker = new RecursiveTextChunker(segmenter);

    @Test
    @DisplayName("按标题切分：每个小节一片，嵌入文本带标题路径，原文不含路径")
    void splitsByHeadingsWithHeadingPath() {
        ParsedDocument document = parser.parse("spring.md", """
                # Spring

                ## IOC

                控制反转是指对象的创建与依赖关系交给容器管理。

                ## AOP

                面向切面编程通过代理在方法前后织入逻辑。
                """);

        List<TextChunk> chunks = headingChunker.chunk(document, ChunkingOptions.defaults());

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("Spring > IOC");
        assertThat(chunks.get(0).content()).startsWith("Spring > IOC");
        assertThat(chunks.get(0).content()).contains("控制反转");
        // 原文片段必须保持 Faithful：不带我们加工的标题路径
        assertThat(chunks.get(0).rawContent()).doesNotContain("Spring > IOC");
        assertThat(chunks.get(1).headingPath()).isEqualTo("Spring > AOP");
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(1).index()).isEqualTo(1);
    }

    @Test
    @DisplayName("三级标题路径：Spring > SpringBoot > 自动配置")
    void buildsNestedHeadingPath() {
        ParsedDocument document = parser.parse("boot.md", """
                # Spring

                ## SpringBoot

                ### 自动配置

                Spring Boot 通过条件装配决定加载哪些 Bean。
                """);

        List<TextChunk> chunks = headingChunker.chunk(document, ChunkingOptions.defaults());

        // 前两个小节只有标题没有正文 -> 不产出切片；只有三级小节有内容
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEqualTo("Spring > SpringBoot > 自动配置");
        assertThat(chunks.get(0).headingLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("代码块：类型为 CODE，且绝不被腰斩（即使超过预算）")
    void neverSplitsCodeBlockInMiddleOfLine() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            code.append("    System.out.println(\"line ").append(i).append("\");\n");
        }
        ParsedDocument document = parser.parse("code.md", """
                # 示例

                ```java
                %s```

                上面是示例代码。
                """.formatted(code));

        // 预算故意设小，逼迫它在代码块上做文章
        List<TextChunk> chunks = headingChunker.chunk(document,
                new ChunkingOptions(80, 10, 20));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anyMatch(chunk -> chunk.type() == ChunkType.CODE);
        // 只检查"代码行"本身：每一行 System.out.println(...) 都必须是完整语句。
        // （切片里可能因为"小块合并"而带上紧随其后的说明文字，这属正常，不应误判）
        chunks.stream()
                .filter(chunk -> chunk.type() == ChunkType.CODE)
                .forEach(chunk -> chunk.rawContent().lines()
                        .filter(line -> line.contains("System.out.println"))
                        .forEach(line -> assertThat(line.strip())
                                .as("代码行不应被腰斩: [%s]", line.strip())
                                .endsWith(");")));
    }

    @Test
    @DisplayName("表格：整体作为一个切片，类型为 TABLE")
    void keepsTableAsOneChunk() {
        ParsedDocument document = parser.parse("table.md", """
                # 对比

                | 组件 | 作用 |
                | --- | --- |
                | Nacos | 注册中心 |
                | Gateway | 网关 |
                | Sentinel | 限流 |
                """);

        List<TextChunk> chunks = headingChunker.chunk(document, ChunkingOptions.defaults());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).type()).isEqualTo(ChunkType.TABLE);
        assertThat(chunks.get(0).rawContent()).contains("Nacos", "Gateway", "Sentinel");
    }

    @Test
    @DisplayName("超长小节：按预算切成多片，且每片都在预算之内")
    void splitsOversizedSectionIntoMultipleChunks() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            body.append("这是第 ").append(i).append(" 段说明文字，用来测试超长小节能否被正确切分。\n\n");
        }
        ParsedDocument document = parser.parse("long.md", """
                # 长文

                ## 小节

                %s""".formatted(body));

        ChunkingOptions options = new ChunkingOptions(100, 20, 30);
        List<TextChunk> chunks = headingChunker.chunk(document, options);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.tokenCount())
                        .as("每片都不应超过预算太多")
                        .isLessThanOrEqualTo(options.maxTokens() + 40));
        // 所有切片都能被检索到同一小节
        assertThat(chunks).allMatch(chunk -> "长文 > 小节".equals(chunk.headingPath()));
    }

    @Test
    @DisplayName("过小的切片并回同小节的上一块（避免产生检索不到的碎片）")
    void mergesTinyChunksWithinSameSection() {
        ParsedDocument document = parser.parse("merge.md", """
                # 主题

                ## 小节一

                这是第一段足够长的内容，用于占据大部分预算，让后面那段显得很短很短。

                短。

                ## 小节二

                另一个小节的内容。
                """);

        List<TextChunk> chunks = headingChunker.chunk(document, new ChunkingOptions(400, 50, 60));

        // "短。" 应该被并进"小节一"，而不是单独成片
        assertThat(chunks).noneMatch(chunk -> chunk.rawContent().strip().equals("短。"));
        assertThat(chunks).anyMatch(chunk -> chunk.rawContent().contains("短。")
                && "主题 > 小节一".equals(chunk.headingPath()));
    }

    @Test
    @DisplayName("偏移量可回溯：切片区间与原文一致")
    void offsetsPointBackToSource() throws Exception {
        ParsedDocument document = parser.parse("offset.md", """
                # 标题

                第一段内容。

                ## 第二节

                第二段内容。
                """);

        List<TextChunk> chunks = headingChunker.chunk(document, ChunkingOptions.defaults());

        // 诊断信息写入 UTF-8 文件：控制台在中文 Windows 上会把中文显示成乱码，
        // 直接读文件才能看清实际值（这是本机排错的标准做法）
        StringBuilder debug = new StringBuilder();
        debug.append("content=[").append(document.content()).append("]\n");
        debug.append("contentLength=").append(document.content().length()).append('\n');
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            debug.append("chunk").append(i)
                    .append(" offsets=[").append(chunk.startOffset()).append(',').append(chunk.endOffset()).append(')')
                    .append(" headingPath=").append(chunk.headingPath())
                    .append(" raw=[").append(chunk.rawContent()).append("]\n");
        }
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target", "chunk-offsets-debug.txt"), debug.toString());

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            assertThat(chunk.startOffset()).as("chunk %d start >= 0", i).isGreaterThanOrEqualTo(0);
            assertThat(chunk.endOffset()).as("chunk %d end within content", i)
                    .isLessThanOrEqualTo(document.content().length());
            assertThat(chunk.startOffset()).as("chunk %d start < end", i).isLessThan(chunk.endOffset());

            String slice = document.content().substring(chunk.startOffset(), chunk.endOffset());
            assertThat(slice.strip()).as("chunk %d 区间内应包含其原文（详见 target/chunk-offsets-debug.txt）", i)
                    .contains(chunk.rawContent().strip());
        }
    }

    @Test
    @DisplayName("contentHash 稳定：同样内容切两次得到同样的哈希（Embedding 缓存键的基础）")
    void contentHashIsStable() {
        String markdown = """
                # 稳定

                ## 小节

                内容保持不变。
                """;

        List<TextChunk> first = headingChunker.chunk(parser.parse("a.md", markdown), ChunkingOptions.defaults());
        List<TextChunk> second = headingChunker.chunk(parser.parse("a.md", markdown), ChunkingOptions.defaults());

        assertThat(first).hasSameSizeAs(second);
        assertThat(first.get(0).contentHash()).isEqualTo(second.get(0).contentHash());
        assertThat(first.get(0).contentHash()).hasSize(64);
    }

    @Test
    @DisplayName("无标题文档：整篇作为一片（不产出空结果）")
    void handlesDocumentWithoutHeadings() {
        ParsedDocument document = parser.parse("plain.txt", "没有任何标题的一段纯文本。");

        List<TextChunk> chunks = headingChunker.chunk(document, ChunkingOptions.defaults());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isNull();
        assertThat(chunks.get(0).rawContent()).contains("纯文本");
    }

    @Test
    @DisplayName("空文档：返回空列表而不是抛异常或产出一条空切片")
    void handlesEmptyDocument() {
        assertThat(headingChunker.chunk(parser.parse("empty.md", ""), ChunkingOptions.defaults())).isEmpty();
        assertThat(headingChunker.chunk(parser.parse("blank.md", "   \n\n  "), ChunkingOptions.defaults())).isEmpty();
    }

    @Test
    @DisplayName("递归切分：长文本切多片且相邻片有重叠（避免答案正好跨在交界处被切丢）")
    void recursiveChunkerAddsOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("第 ").append(i).append(" 段：这是一段用于测试递归切分的文本内容。\n\n");
        }
        ParsedDocument document = parser.parse("recursive.txt", text.toString());

        ChunkingOptions options = new ChunkingOptions(100, 30, 20);
        List<TextChunk> chunks = recursiveChunker.chunk(document, options);

        assertThat(chunks.size()).isGreaterThan(1);
        // 第 2 片起应带上上一片尾部的内容（重叠）
        assertThat(chunks.get(1).content().length())
                .isGreaterThan(chunks.get(1).rawContent().length());
    }

    @Test
    @DisplayName("Token 估算：中文约 1 token/字，英文约 4 字符/token")
    void estimatesTokensReasonably() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        // 10 个汉字 ≈ 10 token
        assertThat(TokenEstimator.estimate("缓存击穿怎么解决")).isBetween(8, 10);
        // 40 个英文字符 ≈ 10 token
        assertThat(TokenEstimator.estimate("abcdefghijklmnopqrstuvwxyzabcdefghijklmn")).isBetween(9, 11);
    }

    @Test
    @DisplayName("切分策略工厂：按策略选实现，非法策略抛 1001")
    void factoryResolvesStrategy() {
        ChunkerFactory factory = new ChunkerFactory(List.of(headingChunker, recursiveChunker));

        assertThat(factory.of(ChunkStrategy.MARKDOWN_HEADING)).isSameAs(headingChunker);
        assertThat(factory.of(ChunkStrategy.RECURSIVE)).isSameAs(recursiveChunker);
        assertThatThrownBy(() -> factory.of("NOT_A_STRATEGY"))
                .hasMessageContaining("不支持的切分策略");
    }
}
