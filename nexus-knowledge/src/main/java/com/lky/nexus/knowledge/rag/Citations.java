package com.lky.nexus.knowledge.rag;

import com.lky.nexus.knowledge.service.ChunkSearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用构建：把资料块里用到的片段转成引用项，并标出回答中真正引用到的编号。
 *
 * <p>抽成独立工具是因为**同步回答与流式回答都要用**：流式在回答结束后才拿到完整文本，
 * 因此引用必然是在末尾一次性补上的（这也决定了 SSE 的事件顺序：
 * meta → delta… → citations → done）。
 */
public final class Citations {

    /** 回答里的引用编号：{@code [1]}、{@code [2,3]}、{@code [1][2]} 都要能认出来。 */
    private static final Pattern CITATION_INDEX = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");

    private Citations() {
    }

    public static List<RagAnswer.RagCitation> build(List<ChunkSearchService.SearchHit> used, String answer) {
        List<Integer> referenced = parseCitedIndexes(answer);
        List<RagAnswer.RagCitation> citations = new ArrayList<>(used.size());
        for (int i = 0; i < used.size(); i++) {
            ChunkSearchService.SearchHit hit = used.get(i);
            int index = i + 1;
            citations.add(new RagAnswer.RagCitation(
                    index, hit.chunkId(), hit.documentId(), hit.documentTitle(), hit.headingPath(),
                    hit.content(), hit.score(), referenced.contains(index)));
        }
        return citations;
    }

    /** 解析回答里的 [n] 标记（支持 {@code [1]}、{@code [1,2]}、{@code [1][2]}）。 */
    public static List<Integer> parseCitedIndexes(String answer) {
        List<Integer> indexes = new ArrayList<>();
        if (answer == null) {
            return indexes;
        }
        Matcher matcher = CITATION_INDEX.matcher(answer);
        while (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                try {
                    int index = Integer.parseInt(part.trim());
                    if (!indexes.contains(index)) {
                        indexes.add(index);
                    }
                } catch (NumberFormatException ignored) {
                    // 不是数字就跳过（例如 [n] 这种占位文本）
                }
            }
        }
        return indexes;
    }
}
