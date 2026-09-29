package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.knowledge.model.ChunkType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分段器：把正文切成一串"尊重结构的块"。
 *
 * <p>它是结构化切分的基础——切分器不再面对裸文本，而是面对
 * "代码块 / 表格 / 列表 / 段落"这四种边界清晰的块，于是可以做到
 * <b>代码块与表格永不被腰斩</b>（被切一半的代码既不可读，向量语义也是噪音）。
 *
 * <p>识别规则刻意保守（宁可少识别，也不要误判）：
 * <ul>
 *   <li>代码块：以 ``` 或 ~~~ 开头的围栏行，到下一个围栏行结束；</li>
 *   <li>表格：一个块里超过半数非空行以 {@code |} 开头；</li>
 *   <li>列表：一个块里超过半数非空行以 {@code -} / {@code *} / {@code +} / {@code 1.} 开头。</li>
 * </ul>
 */
@Component
public class TextSegmenter {

    /** 分段（偏移量从 0 开始）。 */
    public List<Segment> segment(String text) {
        return segment(text, 0);
    }

    /**
     * 分段，并让偏移量相对"原始文档"而不是传入的子串。
     *
     * <p>{@code baseOffset} 是必须的：结构化切分会先把正文按标题切成小节，
     * 再对每个小节的<b>子串</b>分段。如果分段器从 0 开始计数，
     * 得到的偏移量就无法映射回原文档（真实踩坑：所有切片的偏移都变成了小节内的相对位置，
     * 前端据此高亮会定位到完全错误的位置）。
     *
     * @param baseOffset 子串首字符在原始文档中的偏移
     */
    public List<Segment> segment(String text, int baseOffset) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }

        List<String> lines = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        int offset = baseOffset;
        for (String line : text.split("\n", -1)) {
            lines.add(line);
            offsets.add(offset);
            offset += line.length() + 1;
        }

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);

            if (isFence(line)) {
                int start = offsets.get(i);
                int j = i + 1;
                while (j < lines.size() && !isFence(lines.get(j))) {
                    j++;
                }
                // j 可能等于 size（围栏没闭合）——按截断到文末处理，不丢内容
                int lastLine = Math.min(j, lines.size() - 1);
                int end = offsets.get(lastLine) + lines.get(lastLine).length();
                segments.add(new Segment(ChunkType.CODE, join(lines, i, lastLine), start, end));
                i = j + 1;
                continue;
            }

            if (line.isBlank()) {
                i++;
                continue;
            }

            int start = offsets.get(i);
            int j = i;
            while (j < lines.size() && !lines.get(j).isBlank() && !isFence(lines.get(j))) {
                j++;
            }
            int lastLine = j - 1;
            int end = offsets.get(lastLine) + lines.get(lastLine).length();
            String blockText = join(lines, i, lastLine);
            segments.add(new Segment(classify(blockText), blockText, start, end));
            i = j;
        }
        return segments;
    }

    private ChunkType classify(String blockText) {
        String[] blockLines = blockText.split("\n");
        int nonBlank = 0;
        int tableLike = 0;
        int listLike = 0;
        for (String line : blockLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonBlank++;
            if (trimmed.startsWith("|")) {
                tableLike++;
            }
            if (isListItem(trimmed)) {
                listLike++;
            }
        }
        if (nonBlank == 0) {
            return ChunkType.PARAGRAPH;
        }
        if (tableLike * 2 > nonBlank) {
            return ChunkType.TABLE;
        }
        if (listLike * 2 > nonBlank) {
            return ChunkType.LIST;
        }
        return ChunkType.PARAGRAPH;
    }

    private boolean isListItem(String trimmed) {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return true;
        }
        // 有序列表：1. / 12. 等
        int dot = trimmed.indexOf('.');
        return dot > 0 && dot <= 3 && trimmed.substring(0, dot).chars().allMatch(Character::isDigit)
                && trimmed.length() > dot + 1 && trimmed.charAt(dot + 1) == ' ';
    }

    private boolean isFence(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private String join(List<String> lines, int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int k = from; k <= to && k < lines.size(); k++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(k));
        }
        return builder.toString();
    }
}
