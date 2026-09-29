package com.lky.nexus.knowledge.rag;

import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组装：把检索结果拼成给模型的资料块。
 *
 * <p>四件必须做的事：
 * <ol>
 *   <li><b>去重</b>：相邻切片常有重叠（段落切分带 overlap），同一段被塞两次会浪费预算、
 *       还可能让模型以为"这件事被强调了两次"；</li>
 *   <li><b>编号引用</b>：每段前面加 {@code [1][2]}，回答里的引用才有落点——
 *       这是"能点开原文"的前提，也是抑制幻觉最有效的手段之一；</li>
 *   <li><b>Token 预算</b>：超出预算时<b>从最低分开始丢</b>，并且明确告知"资料被截断"，
 *       而不是默默截断让模型以为资料就这些；</li>
 *   <li><b>防注入</b>：资料来自用户自己的文档/PDF，但导入的内容可能包含
 *       "忽略以上指令"之类的文本。因此资料块只作为数据、不做指令解释，
 *       并在系统提示里明确这一点（见 {@link RagAnswerService}）。</li>
 * </ol>
 */
@Component
public class ContextAssembler {

    /** 资料块里每段的字符上限（防止单个超长切片吃掉全部预算）。 */
    private static final int MAX_SEGMENT_CHARS = 1500;

    /**
     * 组装。
     *
     * @param hits        检索结果（已按相关性排序）
     * @param tokenBudget 资料部分的 Token 预算
     */
    public AssembledContext assemble(List<ChunkSearchService.SearchHit> hits, int tokenBudget) {
        List<ChunkSearchService.SearchHit> used = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int tokens = 0;
        int truncated = 0;

        for (ChunkSearchService.SearchHit hit : hits) {
            String content = normalize(hit.content());
            if (content.isEmpty() || isDuplicate(used, content)) {
                continue;
            }
            int cost = com.lky.nexus.knowledge.chunk.TokenEstimator.estimate(content);
            if (tokens + cost > tokenBudget) {
                truncated++;
                continue;
            }
            used.add(hit);
            text.append('[').append(used.size()).append(']');
            if (hit.documentTitle() != null && !hit.documentTitle().isBlank()) {
                text.append('《').append(hit.documentTitle()).append('》');
            }
            if (hit.headingPath() != null && !hit.headingPath().isBlank()) {
                text.append(" › ").append(hit.headingPath());
            }
            text.append('\n').append(content).append("\n\n");
            tokens += cost;
        }

        return new AssembledContext(text.toString().strip(), used, tokens, truncated);
    }

    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        return trimmed.length() > MAX_SEGMENT_CHARS ? trimmed.substring(0, MAX_SEGMENT_CHARS) : trimmed;
    }

    /** 去重：与已选片段相同，或明显被包含（重叠切片的典型形态）。 */
    private boolean isDuplicate(List<ChunkSearchService.SearchHit> used, String content) {
        String head = content.length() > 80 ? content.substring(0, 80) : content;
        for (ChunkSearchService.SearchHit existing : used) {
            String existingContent = normalize(existing.content());
            if (existingContent.contains(head) || content.contains(existingContent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 组装结果。
     *
     * @param text      资料块文本（已编号）
     * @param used      实际用上的命中项（顺序即编号顺序）
     * @param tokens    资料块 Token 估算
     * @param truncated 因预算被丢弃的段数（用于提示与观测）
     */
    public record AssembledContext(String text, List<ChunkSearchService.SearchHit> used,
                                   int tokens, int truncated) {

        public boolean isEmpty() {
            return used.isEmpty();
        }
    }
}
