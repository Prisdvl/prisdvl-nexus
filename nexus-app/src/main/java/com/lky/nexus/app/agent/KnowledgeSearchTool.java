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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

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
                    + "当用户的问题可能涉及他的笔记内容时，必须先调用本工具，再根据检索结果回答。")
    // 描述只讲"这个工具做什么、什么时候调它"。"检索不到时怎么办"不写在这里：
    // 那是回答策略（agent.answer_policy → AgentPrompts 的策略块）的事，
    // 描述与提示词各说一遍就会互相矛盾——而这句话经 MCP tools/list 发给了外部客户端，
    // 更不该替它们规定回答口径（4.4 删掉了原有的"不要凭模型自身的知识回答"那句）。
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
            out.append(emptyExplanation(scoped, knowledgeBases, searchable, skipped, query));
            // 三种"再换关键词也没用"的情形正好是"模型可能改用自身知识回答"的触发条件，
            // 静默降级必须可数（B.27 起的规矩）；四种事实的区分在日志里留全
            observabilityRegistry.increment(ObservabilityRegistry.AGENT_SEARCH_EMPTY);
            log.warn("知识库检索没有可引用资料：userId={} query=「{}」 绑定 {} 个库 / 可检索 {} 个 / 跳过 {}",
                    userId, query, knowledgeBases.size(), searchable.size(), skipped);
        }
        AgentContext.record(toolContext, new AgentContext.ToolCallRecord("knowledge_search", query,
                summary(take, scoped, knowledgeBases, searchable, skipped)));
        return out.toString();
    }

    /**
     * "一条可引用资料都没有"的四种情形，各自如实说出（能混在一起出现，{@code skipped} 逐条列出）。
     *
     * <p><b>只报事实，不报策略</b>：这里不再出现"能不能用自身知识回答"——那是
     * {@code AgentPrompts} 按回答策略追加的那一段的事。工具一旦替模型做决定，
     * 这条规则就有了第二处来源，而 4.4 之前正是"工具与提示词各说一遍"把新用户挡在门外
     * （见 decisions.md B.41）。
     */
    private String emptyExplanation(List<Long> scoped, List<KnowledgeBasePO> knowledgeBases,
                                    List<KnowledgeBasePO> searchable, List<String> skipped, String query) {
        StringBuilder text = new StringBuilder("（");
        if (!searchable.isEmpty()) {
            // 搜了、但没命中：与"没东西可搜"是两件事，前者换一次关键词仍然有意义
            text.append("在").append(scopeLabel(scoped)).append("检索了「").append(query)
                    .append("」，没有匹配的资料。可以换一次关键词再检索；如果仍然没有结果，就不要再调用本工具了。");
        } else {
            if (!knowledgeBases.isEmpty()) {
                text.append(unavailableScopeLabel(scoped)).append("当前都不可用");
            } else if (scoped != null) {
                // 绑定时校验过归属，但库之后可以被删——这种"绑定悬空"要说成"已不存在"，不是"没内容"
                text.append("本 Agent 绑定的知识库都已不存在（可能已被删除）");
            } else {
                text.append("你还没有任何知识库");
            }
            // 这三种情形换关键词都不会有结果：如实说出来，省掉模型"再试一次"的那几轮
            //（实测里 3 次工具调用全烧在空库上、白花 4 秒，见 decisions.md B.41）
            text.append("，没有可检索的资料。不要再用不同关键词重复调用本工具——换词也不会有结果。");
        }
        if (!skipped.isEmpty()) {
            text.append("已跳过：").append(String.join("、", skipped)).append('。');
        }
        return text.append('）').toString();
    }

    /** 检索范围在人话里的说法（未绑定 = 全部知识库）。 */
    private String scopeLabel(List<Long> scoped) {
        return scoped == null ? "全部知识库" : "本 Agent 绑定的 " + scoped.size() + " 个知识库";
    }

    /** "一个都搜不了"时对范围的称呼（覆盖"未绑定但全被禁用"与"绑定但全不可用"两种）。 */
    private String unavailableScopeLabel(List<Long> scoped) {
        return scoped == null ? "你的知识库" : "本 Agent 绑定的知识库";
    }

    /**
     * 界面卡片上那句话：随消息落库，刷新之后仍然能看出"这次有没有东西可引用"。
     *
     * <p>用户据此分辨回答是"检索到了资料"还是"模型改用自身知识了"——所以未命中的情形
     * 必须与"搜了没命中"分开写，不能都笼统成"0 条结果"。
     */
    private String summary(int take, List<Long> scoped, List<KnowledgeBasePO> knowledgeBases,
                           List<KnowledgeBasePO> searchable, List<String> skipped) {
        String suffix = skipped.isEmpty() ? "" : "（跳过 " + skipped.size() + " 个库）";
        if (take > 0) {
            return take + " 条结果" + suffix;
        }
        if (!searchable.isEmpty()) {
            return "0 条结果（无匹配）" + suffix;
        }
        if (!knowledgeBases.isEmpty()) {
            return "知识库全部不可用，0 条结果" + suffix;
        }
        return (scoped != null ? "绑定的知识库均已不存在" : "没有知识库") + "，0 条结果" + suffix;
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
                            chunk.getContent(), 0.1, "like", List.of("关键词匹配"),
                            // 降级路径同样是逐库跑的，库 id 就是手上这个 kbId
                            kbId))
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