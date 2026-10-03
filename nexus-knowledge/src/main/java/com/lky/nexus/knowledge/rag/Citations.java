package com.lky.nexus.knowledge.rag;

import com.lky.nexus.knowledge.service.ChunkSearchService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用构建：把资料块里用到的片段转成引用项，并标出回答中真正引用到的编号。
 *
 * <p>抽成独立工具是因为**同步回答与流式回答都要用**：流式在回答结束后才拿到完整文本，
 * 因此引用必然是在末尾一次性补上的（这也决定了 SSE 的事件顺序：
 * meta → delta… → citations → done）。
 *
 * <p><b>三条链路共用它</b>：知识库问答（同步 / {@code /ask/stream}）与<b>会话流式</b>
 * （RAG 模式与 Agent 模式）。会话流式过去没有落库引用，于是"刷新聊天页面引用就没了"；
 * 修法是<b>把同一个 {@link #build} 的结果写进 {@code chat_message.metadata}</b>，
 * 而不是另写一套引用构造——两套的编号规则一旦分叉，界面上同一个 {@code [2]}
 * 指向的片段就会不一样。
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
                    index, hit.knowledgeBaseId(), hit.chunkId(), hit.documentId(), hit.documentTitle(),
                    hit.headingPath(), hit.content(), hit.score(), referenced.contains(index)));
        }
        return citations;
    }

    /**
     * 多次召回合并成一条引用列表（Agent 模式下模型可能不止调一次工具）。
     *
     * <p><b>为什么必须分组处理</b>：RAG 的 {@code [n]} 是"这一组里的第几段"，
     * 而 Agent 的 {@code [n]} 是<b>每次工具调用各自</b>从 1 开始的（工具无状态，
     * 不知道自己是不是本轮的第二次调用）。所以 {@code referenced} 只能拿
     * <b>它自己那一组</b>的编号去回答正文里比对——拉平之后再算，
     * 第二次调用的第 1 条会被拿去比对第一次调用的 {@code [1]}，结论正好反过来。
     *
     * <p>合并时按 {@code chunkId} 去重：Agent 换个关键词再搜一次，榜首领往往
     * 是同一段资料，界面上出现两遍会让人以为模型看了两份不同的东西。
     * 最后统一重排编号（单组时是恒等变换，RAG 的编号与正文 {@code [n]} 严格对齐）。
     *
     * <p><b>同一段出现在多次召回的多个编号上时，"是否被引用"取并集</b>：
     * 模型写下的 {@code [1]} 究竟指第一次的 {@code [1]} 还是第二次的 {@code [1]}，
     * 从回答正文里无法判别。取并集（只要有一个出现过的编号被引用就算被引用）
     * 与"首见那一份说了算"相比，宁可少标一次"未引用"，也不要指着一份
     * 确实摆在模型面前、且正文引用过的资料说"它没被用上"——后者是错的断言，
     * 而前者只是少了一条提示。
     */
    public static List<RagAnswer.RagCitation> merge(List<List<ChunkSearchService.SearchHit>> groups,
                                                    String answer) {
        List<RagAnswer.RagCitation> merged = new ArrayList<>();
        Map<Long, Integer> positionOfChunk = new HashMap<>();
        for (List<ChunkSearchService.SearchHit> group : groups) {
            for (RagAnswer.RagCitation citation : build(group, answer)) {
                // chunkId 为空（理论上不会有）时无法判重，一律保留
                if (citation.chunkId() == null) {
                    merged.add(citation);
                    continue;
                }
                Integer at = positionOfChunk.get(citation.chunkId());
                if (at == null) {
                    positionOfChunk.put(citation.chunkId(), merged.size());
                    merged.add(citation);
                } else if (citation.referenced() && !merged.get(at).referenced()) {
                    merged.set(at, merged.get(at).referenced(true));
                }
            }
        }
        List<RagAnswer.RagCitation> result = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            result.add(merged.get(i).withIndex(i + 1));
        }
        return result;
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
