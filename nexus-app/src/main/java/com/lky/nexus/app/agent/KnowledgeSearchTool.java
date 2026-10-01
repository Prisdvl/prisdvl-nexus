package com.lky.nexus.app.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.KnowledgeBaseStatus;
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

    /** 模型没给条数时的最终上限（给模型看的条数，不是每个库的召回深度）。 */
    private static final int DEFAULT_PRESENT_LIMIT = 5;
    /** 给模型看的条数上限：再多会挤占上下文，也超出"让模型看一眼"的需要。 */
    private static final int MAX_PRESENT_LIMIT = 10;

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
            // 两处一起才算把"可选"表达清楚（4.3 真机实测踩到，见 decisions.md B.39）：
            //   1. required = false：@ToolParam 的 required 默认就是 true（javap 读出来的），
            //      不写这一句，框架反射出的 schema 会把 topK 标成必填——与本行"默认 5"自相矛盾，
            //      MCP 客户端据此以为漏传就是错，而这个参数的语义从来是可选的；
            //   2. 包装类型 Integer 而不是 int：漏传时 JSON 的缺省值绑到基本类型上会得到 null，
            //      拆箱抛 NullPointerException（"Cannot invoke Number.intValue()"）。
            // 这两条 4.3 之前都被盖住了——MCP 那条路自己写了 arguments.path("topK").asInt(5)
            // 兜住默认值，而模型那条路永远照 required 的 schema 传参；
            // 4.3 把参数原样交给工具（正确做法）之后，矛盾立刻变成一次真实的 400/工具失败。
            @ToolParam(description = "返回条数，默认 5，最大 10", required = false) Integer topK,
            ToolContext toolContext) {
        Long userId = AgentContext.requireUserId(toolContext);
        // null（漏传）与 <= 0（传了 0 或负数）都走默认值：两者都是"没指定"，不该有不同下场
        int limit = Math.clamp(topK == null || topK <= 0 ? DEFAULT_PRESENT_LIMIT : topK,
                1, MAX_PRESENT_LIMIT);
        // 先报"开始检索"：流式对话据此把"正在检索知识库…"实时推给用户，
        // 而不是让用户对着空屏等几秒（检索本身要走 ES + pgvector）
        AgentContext.call(toolContext, "knowledge_search", query);

        // 检索范围：Agent 绑定知识库时只搜绑定的那些；没绑定（scoped == null）沿用老行为搜全部。
        // 范围来自 ToolContext 而不是工具入参——模型既不知道知识库 id，也不该有能力指定别人的库
        List<Long> scoped = AgentContext.kbIds(toolContext);
        List<KnowledgeBasePO> knowledgeBases = knowledgeBaseMapper.selectList(
                Wrappers.<KnowledgeBasePO>lambdaQuery()
                        .eq(KnowledgeBasePO::getUserId, userId)
                        .in(scoped != null, KnowledgeBasePO::getId, scoped));

        // 被禁用的库不参与检索（V10 起 status 字段的语义承诺），并把"跳过了什么"如实说出来：
        // 悄悄少搜几个库的表现是"明明有资料却回答不知道"，用户无从判断是检索坏了还是库被禁了
        List<KnowledgeBasePO> searchable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (KnowledgeBasePO kb : knowledgeBases) {
            if (KnowledgeBaseStatus.parse(kb.getStatus()) == KnowledgeBaseStatus.DISABLED) {
                skipped.add("「" + kb.getName() + "」已禁用");
            } else {
                searchable.add(kb);
            }
        }
        if (scoped != null) {
            // 绑定时校验过归属，但库之后可以被删：这种"绑定悬空"同样要说出来
            Set<Long> existing = knowledgeBases.stream().map(KnowledgeBasePO::getId).collect(Collectors.toSet());
            for (Long kbId : scoped) {
                if (!existing.contains(kbId)) {
                    skipped.add("知识库 #" + kbId + " 已不存在");
                }
            }
        }

        List<ChunkSearchService.SearchHit> all = new ArrayList<>();
        for (KnowledgeBasePO kb : searchable) {
            // 召回条数交给知识库自己的 retrieval_top_k（topK 传 null = 没指定），
            // 而不是在这里写死 5：工具的参数是"最终给模型看几条"，
            // 与"每个库召回多深"不是同一件事，混在一起会让按库调参对 Agent 无效
            int recallTopK = kb.getRetrievalTopK() == null ? DEFAULT_PRESENT_LIMIT : kb.getRetrievalTopK();
            try {
                all.addAll(chunkSearchService.search(userId, kb.getId(), query, null, SearchMode.HYBRID));
            } catch (Exception ex) {
                List<ChunkSearchService.SearchHit> fallback = likeSearch(kb.getId(), query, recallTopK);
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
        if (scoped != null) {
            out.append("检索范围：本 Agent 绑定的 ").append(scoped.size()).append(" 个知识库");
            if (!skipped.isEmpty()) {
                out.append("（已跳过：").append(String.join("、", skipped)).append("）");
            }
            out.append('\n');
        }
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
            if (scoped != null && searchable.isEmpty()) {
                // "绑了库但一个都搜不了"与"搜了但没命中"必须分开说：
                // 前者模型只能如实说检索不可用，不能退回去用自身知识编造（那正是关掉幻觉的初衷）
                out.append("（本 Agent 绑定的知识库当前都不可用，无法检索到任何资料。"
                        + "请如实告知用户检索不可用，不要用你自己的知识回答。）");
            } else {
                out.append("（没有检索到相关结果。若问题确实与笔记无关，请如实说明。）");
            }
        }
        AgentContext.record(toolContext, new AgentContext.ToolCallRecord("knowledge_search", query,
                take + " 条结果" + (skipped.isEmpty() ? "" : "（跳过 " + skipped.size() + " 个库）")));
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