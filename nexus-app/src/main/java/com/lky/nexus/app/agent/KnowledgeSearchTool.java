package com.lky.nexus.app.agent;

import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 知识库检索工具（Phase 5）：让模型能主动检索用户的笔记。
 *
 * <p>跨全部知识库搜索（模型不知道用户的库 id，也不能要求它知道），
 * 命中结果带上"来源文档"，模型据此给出带出处的回答。
 * 正常情况下代理模型的工具调用是自动执行的（由 Spring AI 的 ToolCallingManager 完成，有轮数上限）。
 */
@Component
public class KnowledgeSearchTool {

    private final ChunkSearchService chunkSearchService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeSearchTool(ChunkSearchService chunkSearchService,
                               KnowledgeBaseMapper knowledgeBaseMapper) {
        this.chunkSearchService = chunkSearchService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Tool(name = "knowledge_search",
            description = "检索当前用户知识库中的资料片段（混合检索：BM25 + 向量 + RRF 融合）。"
                    + "当用户的问题可能涉及他的笔记内容时，必须先调用本工具，再根据检索结果回答；"
                    + "不要凭模型自身的知识回答与知识库相关的问题。")
    public String search(
            @ToolParam(description = "检索关键词或问题，用中文自然语言表述") String query,
            @ToolParam(description = "返回条数，默认 5，最大 10") int topK) {
        Long userId = AgentContext.requireUserId();
        int limit = Math.clamp(topK <= 0 ? 5 : topK, 1, 10);

        List<ChunkSearchService.SearchHit> all = new ArrayList<>();
        List<KnowledgeBasePO> knowledgeBases = knowledgeBaseMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<KnowledgeBasePO>lambdaQuery()
                        .eq(KnowledgeBasePO::getUserId, userId));
        for (KnowledgeBasePO kb : knowledgeBases) {
            try {
                // 检索基础设施缺失时（如 standalone / H2），单库失败不能拖垮整个工具
                all.addAll(chunkSearchService.search(userId, kb.getId(), query, 5, SearchMode.HYBRID));
            } catch (Exception ex) {
                AgentContext.record(new AgentContext.ToolCallRecord("knowledge_search", query,
                        "[跳过知识库 " + kb.getName() + "] 检索基础设施不可用：" + ex.getMessage()));
            }
        }
        all.sort(Comparator.comparingDouble(ChunkSearchService.SearchHit::score).reversed());

        int take = Math.min(limit, all.size());
        StringBuilder out = new StringBuilder();
        out.append("检索到 ").append(take).append(" 条结果：\n");
        for (int i = 0; i < take; i++) {
            ChunkSearchService.SearchHit hit = all.get(i);
            out.append("[").append(i + 1).append("] 来源: ")
                    .append(hit.documentTitle()).append(" › ").append(hit.headingPath())
                    .append('\n')
                    .append(clip(hit.content(), 400)).append('\n');
        }
        if (take == 0) {
            out.append("（没有检索到相关结果。若问题确实与笔记无关，请如实说明。）");
        }
        // 每一次调用都留痕——"用户可控"的第一步是"用户可见"
        AgentContext.record(new AgentContext.ToolCallRecord("knowledge_search", query, take + " 条结果"));
        return out.toString();
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}