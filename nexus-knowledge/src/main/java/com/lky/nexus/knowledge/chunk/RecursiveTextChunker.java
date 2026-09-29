package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.common.util.HashUtils;
import com.lky.nexus.knowledge.model.ChunkStrategy;
import com.lky.nexus.knowledge.model.ChunkType;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归切分（无结构文本用）。
 *
 * <p>与标题切分的区别：没有标题可用，因此靠<b>段落 → 句子 → 行</b>逐级回退，
 * 并保留一定的<b>重叠</b>（后端补偿：相邻切片共享少量文本，
 * 避免"答案正好跨在两片交界处"时被切丢）。
 *
 * <p>重叠只在本策略里使用：标题切分有 headingPath 提供上下文，不需要重叠。
 */
@Component
public class RecursiveTextChunker implements DocumentChunker {

    private final TextSegmenter segmenter;

    public RecursiveTextChunker(TextSegmenter segmenter) {
        this.segmenter = segmenter;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.RECURSIVE;
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument document, ChunkingOptions options) {
        String content = document.content();
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<Segment> segments = segmenter.segment(content);
        List<TextChunk> chunks = new ArrayList<>();

        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        int bufferStart = -1;
        int bufferEnd = -1;
        ChunkType bufferType = ChunkType.PARAGRAPH;
        List<String> overlapCarry = new ArrayList<>();

        for (Segment segment : segments) {
            int segmentTokens = TokenEstimator.estimate(segment.text());

            if (segmentTokens > options.maxTokens()) {
                // 超大段落：先冲刷，再按句子/行回退切分
                if (bufferTokens > 0) {
                    chunks.add(build(bufferType, buffer.toString(), bufferStart, bufferEnd, overlapCarry, options));
                    overlapCarry = tailForOverlap(buffer.toString(), options.overlapTokens());
                    buffer.setLength(0);
                    bufferTokens = 0;
                    bufferStart = -1;
                }
                for (Segment piece : splitOversized(segment, options)) {
                    chunks.add(build(piece.type(), piece.text(), piece.start(), piece.end(), overlapCarry, options));
                    overlapCarry = tailForOverlap(piece.text(), options.overlapTokens());
                }
                continue;
            }

            if (bufferTokens > 0 && bufferTokens + segmentTokens > options.maxTokens()) {
                chunks.add(build(bufferType, buffer.toString(), bufferStart, bufferEnd, overlapCarry, options));
                overlapCarry = tailForOverlap(buffer.toString(), options.overlapTokens());
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

        if (buffer.length() > 0) {
            chunks.add(build(bufferType, buffer.toString(), bufferStart, bufferEnd, overlapCarry, options));
        }
        return reindex(chunks);
    }

    /** 取尾部若干段落作为重叠内容（按 token 预算，不切断段落）。 */
    private List<String> tailForOverlap(String text, int overlapTokens) {
        if (overlapTokens <= 0) {
            return List.of();
        }
        String[] paragraphs = text.split("\n\n");
        List<String> tail = new ArrayList<>();
        int tokens = 0;
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            int paragraphTokens = TokenEstimator.estimate(paragraphs[i]);
            if (tokens + paragraphTokens > overlapTokens && !tail.isEmpty()) {
                break;
            }
            tail.add(0, paragraphs[i]);
            tokens += paragraphTokens;
        }
        return tail;
    }

    private List<Segment> splitOversized(Segment segment, ChunkingOptions options) {
        List<Segment> pieces = new ArrayList<>();
        // 先按句子切，再按行切——尽量切在语义边界上
        String[] sentences = segment.text().split("(?<=[。！？；.!?;])\\s*");
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        int pieceStart = segment.start();
        int cursor = segment.start();

        for (String sentence : sentences) {
            int sentenceTokens = TokenEstimator.estimate(sentence);
            if (bufferTokens > 0 && bufferTokens + sentenceTokens > options.maxTokens()) {
                pieces.add(new Segment(segment.type(), buffer.toString(), pieceStart, cursor));
                buffer.setLength(0);
                bufferTokens = 0;
                pieceStart = cursor;
            }
            buffer.append(sentence);
            bufferTokens += sentenceTokens;
            cursor += sentence.length();
        }
        if (buffer.length() > 0) {
            pieces.add(new Segment(segment.type(), buffer.toString(), pieceStart, segment.end()));
        }
        return pieces;
    }

    private TextChunk build(ChunkType type, String text, int start, int end,
                            List<String> overlapCarry, ChunkingOptions options) {
        String rawContent = text.strip();
        StringBuilder withOverlap = new StringBuilder();
        for (String carry : overlapCarry) {
            if (!rawContent.startsWith(carry.strip())) {
                withOverlap.append(carry.strip()).append("\n\n");
            }
        }
        withOverlap.append(rawContent);
        String content = withOverlap.toString();
        int tokens = TokenEstimator.estimate(content);
        return new TextChunk(-1, type, content, rawContent, null, null, null,
                start, end, tokens, content.length(), HashUtils.sha256Hex(content));
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
