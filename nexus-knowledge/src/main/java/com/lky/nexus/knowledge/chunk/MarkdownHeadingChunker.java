package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.common.util.HashUtils;
import com.lky.nexus.knowledge.model.ChunkStrategy;
import com.lky.nexus.knowledge.model.ChunkType;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 Markdown 标题层级切分（默认策略）。
 *
 * <p>核心思路：<b>标题就是语义边界</b>。先按标题把正文切成"小节"，
 * 小节内再按块（段落/代码/表格/列表）打包到 token 预算内。
 *
 * <p>三个关键约定：
 * <ol>
 *   <li><b>嵌入文本带标题路径</b>：{@code content = "Spring > SpringBoot > 自动配置" + 正文}。
 *       这样"只有一句话的小节"也能被检索到，而且切片脱离文档后依然自解释；</li>
 *   <li><b>代码块与表格不参与跨块合并的截断</b>：它们作为整块进入切片，
 *       实在超预算时只按行边界切（绝不切断一行代码）；</li>
 *   <li><b>标题级切分不做重叠</b>：标题路径已经提供了上下文，
 *       再叠加重叠只会让相邻切片高度相似、挤占检索结果。</li>
 * </ol>
 */
@Component
public class MarkdownHeadingChunker implements DocumentChunker {

    private final TextSegmenter segmenter;

    public MarkdownHeadingChunker(TextSegmenter segmenter) {
        this.segmenter = segmenter;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.MARKDOWN_HEADING;
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument document, ChunkingOptions options) {
        String content = document.content();
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<Section> sections = splitSections(document, content);
        List<TextChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            chunkSection(section, options, chunks);
        }
        return reindex(mergeTooSmall(chunks, options));
    }

    // ------------------------------------------------------------------
    // 按标题切小节
    // ------------------------------------------------------------------

    private record Section(String heading, String headingPath, Integer headingLevel,
                           int start, int end, int bodyStart, String body) {
    }

    private List<Section> splitSections(ParsedDocument document, String content) {
        List<ParsedDocument.Heading> headings = document.headings();
        List<Section> sections = new ArrayList<>();

        if (headings.isEmpty()) {
            sections.add(new Section(null, null, null, 0, content.length(), 0, content));
            return sections;
        }

        // 第一个标题之前可能还有引言内容，单独成节（无标题路径）
        if (headings.get(0).startOffset() > 0) {
            sections.add(new Section(null, null, null, 0, headings.get(0).startOffset(), 0,
                    content.substring(0, headings.get(0).startOffset())));
        }

        for (int i = 0; i < headings.size(); i++) {
            ParsedDocument.Heading heading = headings.get(i);
            int start = heading.startOffset();
            int end = (i + 1 < headings.size()) ? headings.get(i + 1).startOffset() : content.length();

            int headingLineEnd = content.indexOf('\n', start);
            if (headingLineEnd < 0 || headingLineEnd > end) {
                headingLineEnd = end;
            } else {
                headingLineEnd++;
            }
            int bodyStart = Math.min(headingLineEnd, end);
            String body = content.substring(bodyStart, end);

            sections.add(new Section(heading.text(), buildHeadingPath(headings, i), heading.level(),
                    start, end, bodyStart, body));
        }
        return sections;
    }

    /**
     * 构建标题路径：向上找到所有祖先标题，例如 {@code Spring > SpringBoot > 自动配置}。
     *
     * <p>用"层级栈"的做法：从当前标题往前回溯，遇到层级更小的就是祖先。
     */
    private String buildHeadingPath(List<ParsedDocument.Heading> headings, int index) {
        List<String> path = new ArrayList<>();
        int currentLevel = headings.get(index).level();
        path.add(headings.get(index).text());
        for (int i = index - 1; i >= 0; i--) {
            if (headings.get(i).level() < currentLevel) {
                path.add(0, headings.get(i).text());
                currentLevel = headings.get(i).level();
                if (currentLevel == 1) {
                    break;
                }
            }
        }
        return String.join(" > ", path);
    }

    // ------------------------------------------------------------------
    // 小节内打包成切片
    // ------------------------------------------------------------------

    private void chunkSection(Section section, ChunkingOptions options, List<TextChunk> chunks) {
        // 关键：把 bodyStart 作为基准偏移传进去，使分段结果的偏移量相对整篇正文
        List<Segment> segments = segmenter.segment(section.body(), section.bodyStart());
        if (segments.isEmpty()) {
            // 只有标题、没有正文的小节不产出切片：
            // 它的语义已经通过子标题的 headingPath 传递下去了，单独留一条空切片只会污染检索。
            return;
        }

        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        int bufferStart = -1;
        int bufferEnd = -1;
        ChunkType bufferType = ChunkType.PARAGRAPH;

        for (Segment segment : segments) {
            int segmentTokens = TokenEstimator.estimate(segment.text());

            // 超大块：先把已攒的内容冲刷掉，再把它自己按行边界切开
            if (segmentTokens > options.maxTokens()) {
                flush(section, chunks, buffer, bufferTokens, bufferStart, bufferEnd, bufferType);
                buffer.setLength(0);
                bufferTokens = 0;
                bufferStart = -1;
                for (Segment piece : splitOversized(segment, options)) {
                    flush(section, chunks, new StringBuilder(piece.text()),
                            TokenEstimator.estimate(piece.text()), piece.start(), piece.end(), piece.type());
                }
                continue;
            }

            if (bufferTokens > 0 && bufferTokens + segmentTokens > options.maxTokens()) {
                flush(section, chunks, buffer, bufferTokens, bufferStart, bufferEnd, bufferType);
                buffer.setLength(0);
                bufferTokens = 0;
                bufferStart = -1;
            }

            if (bufferStart < 0) {
                bufferStart = segment.start();
                bufferType = segment.type();
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(segment.text());
            bufferTokens += segmentTokens;
            bufferEnd = segment.end();
        }

        flush(section, chunks, buffer, bufferTokens, bufferStart, bufferEnd, bufferType);
    }

    private void flush(Section section, List<TextChunk> chunks, StringBuilder buffer, int tokens,
                       int start, int end, ChunkType type) {
        if (buffer.length() == 0 || tokens <= 0) {
            return;
        }
        String rawContent = buffer.toString().strip();
        if (rawContent.isEmpty()) {
            return;
        }
        chunks.add(build(section.heading(), section.headingPath(), section.headingLevel(),
                type, rawContent, start, end, tokens));
    }

    /**
     * 超大块按行边界切分。
     *
     * <p>只在这里"硬切"，且只切在换行处：代码与表格被腰斩比切得不够精细糟糕得多。
     */
    private List<Segment> splitOversized(Segment segment, ChunkingOptions options) {
        List<Segment> pieces = new ArrayList<>();
        String[] lines = segment.text().split("\n", -1);

        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        int pieceStart = segment.start();
        int cursor = segment.start();

        for (String line : lines) {
            int lineTokens = TokenEstimator.estimate(line);
            if (bufferTokens > 0 && bufferTokens + lineTokens > options.maxTokens()) {
                pieces.add(new Segment(segment.type(), buffer.toString(), pieceStart, cursor - 1));
                buffer.setLength(0);
                bufferTokens = 0;
                pieceStart = cursor;
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);
            bufferTokens += lineTokens;
            cursor += line.length() + 1;
        }
        if (buffer.length() > 0) {
            pieces.add(new Segment(segment.type(), buffer.toString(), pieceStart,
                    segment.end()));
        }
        return pieces;
    }

    // ------------------------------------------------------------------
    // 合并过小切片 / 收尾
    // ------------------------------------------------------------------

    /**
     * 把过小的切片并回上一块（仅当两者标题路径相同）。
     *
     * <p>为什么要合并：切分会产生"某某小节只有一句话"的碎片，
     * 它们单独成片时几乎无法被检索到，还会挤占 TopK。合并后既保留了内容，
     * 也不会把不相干的两节混在一起（限定同一 headingPath）。
     */
    private List<TextChunk> mergeTooSmall(List<TextChunk> chunks, ChunkingOptions options) {
        List<TextChunk> merged = new ArrayList<>();
        for (TextChunk chunk : chunks) {
            if (!merged.isEmpty()) {
                TextChunk previous = merged.get(merged.size() - 1);
                boolean sameSection = java.util.Objects.equals(previous.headingPath(), chunk.headingPath());
                boolean tooSmall = chunk.tokenCount() < options.minTokens();
                boolean fits = previous.tokenCount() + chunk.tokenCount() <= options.maxTokens();
                if (sameSection && tooSmall && fits) {
                    merged.set(merged.size() - 1, merge(previous, chunk));
                    continue;
                }
            }
            merged.add(chunk);
        }
        return merged;
    }

    private TextChunk merge(TextChunk previous, TextChunk next) {
        String rawContent = previous.rawContent() + "\n\n" + next.rawContent();
        String content = withHeadingPath(previous.headingPath(), rawContent);
        int tokens = TokenEstimator.estimate(content);
        return new TextChunk(previous.index(), previous.type(), content, rawContent,
                previous.heading(), previous.headingPath(), previous.headingLevel(),
                previous.startOffset(), next.endOffset(), tokens, content.length(),
                HashUtils.sha256Hex(content));
    }

    private TextChunk build(String heading, String headingPath, Integer headingLevel, ChunkType type,
                            String rawContent, int start, int end, int tokens) {
        String content = withHeadingPath(headingPath, rawContent);
        int contentTokens = TokenEstimator.estimate(content);
        return new TextChunk(-1, type, content, rawContent, heading, headingPath, headingLevel,
                start, end, contentTokens, content.length(), HashUtils.sha256Hex(content));
    }

    /** 嵌入文本 = 标题路径 + 正文（标题路径让切片自解释，见类注释）。 */
    private String withHeadingPath(String headingPath, String rawContent) {
        return headingPath == null || headingPath.isBlank()
                ? rawContent
                : headingPath + "\n\n" + rawContent;
    }

    private List<TextChunk> reindex(List<TextChunk> chunks) {
        List<TextChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            result.add(new TextChunk(i, chunk.type(), chunk.content(), chunk.rawContent(),
                    chunk.heading(), chunk.headingPath(), chunk.headingLevel(),
                    chunk.startOffset(), chunk.endOffset(), chunk.tokenCount(),
                    chunk.charCount(), chunk.contentHash()));
        }
        return result;
    }
}
