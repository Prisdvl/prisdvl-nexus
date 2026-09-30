package com.lky.nexus.app.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（Phase 5）：让模型能主动检索用户的笔记。
 *
 * <p>检索的<b>降级阶梯</b>（能走到哪一层取决于部署环境，全部如实告知模型）：
 * <ol>
 *   <li>混合检索（BM25 + 向量 + RRF）——完整基础设施（ES + pgvector）；</li>
 *   <li>SQL LIKE 关键词匹配——精确检索不可用时（例如 standalone / 内存库）的兜底，
 *       质量更低但至少不要让模型"空手"；</li>
 *   <li>两者都不可用 → 工具直接说明，模型如实回答"检索不可用"。</li>
 * </ol>
 */
@Component
public class KnowledgeSearchTool {

    private final ChunkSearchService chunkSearchService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final ObservabilityRegistry observabilityRegistry;

    public KnowledgeSearchTool(ChunkSearchService chunkSearchService,
                               KnowledgeBaseMapper knowledgeBaseMapper,
                               ChunkMapper chunkMapper,
                               DocumentMapper documentMapper,
                               ObservabilityRegistry observabilityRegistry) {
        this.chunkSearchService = chunkSearchService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.observabilityRegistry = observabilityRegistry;
    }

    @Tool(name = "knowledge_search",
            description = "检索当前用户知识库中的资料片段（混合检索：BM25 + 向量 + RRF 融合）。"
                    + "当用户的问题可能涉及他的笔记内容时，必须先调用本工具，再根据检索结果回答；"
                    + "不要凭模型自身的知识回答与知识库相关的问题。")
    public String search(
            @ToolParam(description = "检索关键词或问题，用中文自然语言表述") String query,
            @ToolParam(description = "返回条数，默认 5，最大 10") int topK,
            ToolContext toolContext) {
        Long userId = AgentContext.requireUserId(toolContext);
        int limit = Math.clamp(topK <= 0 ? 5 : topK, 1, 10);
        // 先报"开始检索"：流式对话据此把"正在检索知识库…"实时推给用户，
        // 而不是让用户对着空屏等几秒（检索本身要走 ES + pgvector）
        AgentContext.call(toolContext, "knowledge_search", query);

        List<ChunkSearchService.SearchHit> all = new ArrayList<>();
        List<KnowledgeBasePO> knowledgeBases = knowledgeBaseMapper.selectList(
                Wrappers.<KnowledgeBasePO>lambdaQuery().eq(KnowledgeBasePO::getUserId, userId));
        for (KnowledgeBasePO kb : knowledgeBases) {
            try {
                all.addAll(chunkSearchService.search(userId, kb.getId(), query, 5, SearchMode.HYBRID));
            } catch (Exception ex) {
                List<ChunkSearchService.SearchHit> fallback = likeSearch(kb.getId(), query, 5);
                if (!fallback.isEmpty()) {
                    all.addAll(fallback);
                    observabilityRegistry.increment(ObservabilityRegistry.AGENT_SEARCH_DEGRADED);
                    AgentContext.record(toolContext, new AgentContext.ToolCallRecord("knowledge_search", query,
                            "知识库「" + kb.getName() + "」精确检索不可用，已降级为关键词匹配（"
                                    + fallback.size() + " 条）"));
                } else {
                    observabilityRegistry.increment(ObservabilityRegistry.AGENT_SEARCH_UNAVAILABLE);
                    AgentContext.record(toolContext, new AgentContext.ToolCallRecord("knowledge_search", query,
                            "知识库「" + kb.getName() + "」检索不可用：" + ex.getMessage()));
                }
            }
        }
        all.sort(Comparator.comparingDouble(ChunkSearchService.SearchHit::score).reversed());

        int take = Math.min(limit, all.size());
        StringBuilder out = new StringBuilder();
        out.append("检索到 ").append(take).append(" 条结果：\n");
        for (int i = 0; i < take; i++) {
            ChunkSearchService.SearchHit hit = all.get(i);
            out.append("[").append(i + 1).append("] 来源: ").append(hit.documentTitle());
            // 标题路径可能没有（降级匹配、或文档无标题层级）：这时不能把 "null" 写进模型的上下文，
            // 模型会照抄到回答里（实测出现过"来源: 缓存击穿 › null"这种用户可见的噪声）
            if (StringUtils.hasText(hit.headingPath())) {
                out.append(" › ").append(hit.headingPath());
            }
            out.append('\n').append(clip(hit.content(), 400)).append('\n');
        }
        if (take == 0) {
            out.append("（没有检索到相关结果。若问题确实与笔记无关，请如实说明。）");
        }
        AgentContext.record(toolContext, new AgentContext.ToolCallRecord("knowledge_search", query, take + " 条结果"));
        return out.toString();
    }

    /** 第二级降级：SQL LIKE 关键词匹配。
     *
     * <p>中文没有分词器时不能拿完整问题做 LIKE（"缓存击穿怎么解决"不会出现在正文里）。
     * 做法：先剥掉问句助词，再按"整句 → 前 6 字 → 前 4 字 → 前 2 字"逐级缩短，
     * 第一个命中的词就用它（"缓存击穿怎么解决" → "缓存击穿" → 命中正文里出现过的词）。
     */
    private List<ChunkSearchService.SearchHit> likeSearch(Long kbId, String query, int limit) {
        String clean = query == null ? "" : query.trim()
                .replaceAll("[的了吗呢么怎样怎么如何为啥为什么是什么有没有在和我则帮]", "");
        if (clean.length() < 2) {
            clean = query == null ? "" : query.trim();
        }
        int[] cuts = {clean.length(), Math.min(6, clean.length()),
                Math.min(4, clean.length()), Math.min(2, clean.length())};
        List<ChunkSearchService.SearchHit> last = List.of();
        for (int cut : cuts) {
            if (cut < 2 || cut > clean.length()) {
                continue;
            }
            last = likeSingle(kbId, clean.substring(0, cut), limit);
            if (!last.isEmpty()) {
                return last;
            }
        }
        return last;
    }

    private List<ChunkSearchService.SearchHit> likeSingle(Long kbId, String term, int limit) {
        try {
            List<ChunkPO> chunks = chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                    .eq(ChunkPO::getKbId, kbId)
                    .like(ChunkPO::getContent, term)
                    .orderByDesc(ChunkPO::getCreatedAt)
                    .last("LIMIT " + limit));
            if (chunks.isEmpty()) {
                return List.of();
            }
            Set<Long> documentIds = chunks.stream()
                    .map(ChunkPO::getDocumentId).collect(Collectors.toSet());
            Map<Long, String> titles = new HashMap<>();
            for (DocumentPO document : documentMapper.selectBatchIds(documentIds)) {
                titles.put(document.getId(), document.getTitle());
            }
            return chunks.stream()
                    .map(chunk -> new ChunkSearchService.SearchHit(
                            chunk.getId(), chunk.getDocumentId(),
                            titles.getOrDefault(chunk.getDocumentId(), "未命名文档"),
                            // 切片自带标题路径，降级路径也要带上：模型据此说明"资料出自哪一节"
                            StringUtils.hasText(chunk.getHeadingPath())
                                    ? chunk.getHeadingPath()
                                    : chunk.getHeading(),
                            chunk.getContent(), 0.1, "like", List.of("关键词匹配")))
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}