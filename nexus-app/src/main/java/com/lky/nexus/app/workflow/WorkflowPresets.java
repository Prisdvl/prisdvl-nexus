package com.lky.nexus.app.workflow;

import com.lky.nexus.app.workflow.model.WorkflowEdge;
import com.lky.nexus.app.workflow.model.WorkflowGraph;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内置工作流模板（「预置工作流」）。
 *
 * <p><b>与设计稿的差异要明说</b>：`architecture.md` §12 / `roadmap` 8.5 画的那套流程依赖
 * `PARALLEL` 与 `TOOL` 节点，而实现里的枚举只有
 * `START / KNOWLEDGE_RETRIEVAL / LLM / AGENT / IF_ELSE / TEMPLATE / HUMAN_APPROVAL / END`。
 * 所以这里给的是用现有节点写出的**顺序版**：检索 → 模型 → 渲染；「7 天学习计划」额外插一个人工审批闸门。
 * 没有并行扇出、没有工具节点——这不是偷懒，是不想为了模板去虚构节点类型。
 *
 * <p>模板只是<b>脚手架</b>：`KNOWLEDGE_RETRIEVAL` 的 `kbIds` 刻意留空（校验器要求非空），
 * 所以实例化出来的工作流在用户挑好知识库之前是「结构完整但校验不通过」——这是预期行为，
 * 由 validate 明确告诉他缺什么，而不是偷偷塞一个假知识库。
 */
@Component
public class WorkflowPresets {

    private static final int SCHEMA_VERSION = 1;

    /** 一个模板：code 稳定（文档与前端引用它），图在实例化时才写成某个用户的草稿。 */
    public record Preset(String code, String name, String description, WorkflowGraph graph) {
    }

    private final List<Preset> presets;

    public WorkflowPresets() {
        this.presets = List.of(knowledgeGapAnalysis(), studyPlan7d(), knowledgeReport());
    }

    public List<Preset> all() {
        return presets;
    }

    public Preset require(String code) {
        return presets.stream()
                .filter(preset -> preset.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "预置工作流不存在: " + code));
    }

    /* ---------------- 三个模板 ---------------- */

    private static Preset knowledgeGapAnalysis() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("retrieval", "KNOWLEDGE_RETRIEVAL", 200, retrievalConfig()),
                node("analysis", "LLM", 380, Map.of(
                        "temperature", 0.2,
                        "userPrompt", """
                                以下是从我的知识库检索到的片段：
                                {{ nodes.retrieval.output.documents }}

                                请做一次「知识缺口分析」，输出三部分：
                                1) 已覆盖的主题（按片段能支撑的程度排序）；
                                2) 明显缺失或薄弱的知识点；
                                3) 每条结论都必须引用上面片段里的标题或原文片段作为证据。
                                如果片段为空，直接回答「没有检索到资料」，不要编造。""")),
                node("report", "TEMPLATE", 580, Map.of(
                        "template", "## 知识缺口分析\n\n{{ nodes.analysis.output.text }}")),
                node("end", "END", 760, Map.of()));
        return new Preset("knowledge-gap-analysis", "知识缺口分析",
                "检索知识库后让模型给出覆盖度与缺口清单，每条结论要求附证据。实例化后先选知识库再发布。",
                new WorkflowGraph(SCHEMA_VERSION, nodes, chain("start", "retrieval", "analysis", "report", "end")));
    }

    private static Preset studyPlan7d() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("retrieval", "KNOWLEDGE_RETRIEVAL", 200, retrievalConfig()),
                node("plan", "LLM", 380, Map.of(
                        "temperature", 0.4,
                        "userPrompt", """
                                以下是从我的知识库检索到的片段：
                                {{ nodes.retrieval.output.documents }}

                                请基于这些片段制定一个 7 天学习计划：每天 1 个主题、3 条以内可执行任务，
                                并说明每个主题对应哪些已有笔记（给出标题）。片段不足以支撑的部分要显式标注「缺少资料」。""")),
                // 人工闸门：先让人看一眼计划，再渲染成最终产出
                node("approval", "HUMAN_APPROVAL", 560, Map.of()),
                node("report", "TEMPLATE", 720, Map.of(
                        "template", "## 7 天学习计划（已人工确认）\n\n{{ nodes.plan.output.text }}")),
                node("end", "END", 880, Map.of()));
        return new Preset("study-plan-7d", "7 天学习计划",
                "检索笔记后生成 7 天学习计划，并在渲染前插入一道人工审批闸门。实例化后先选知识库再发布。",
                new WorkflowGraph(SCHEMA_VERSION, nodes,
                        chain("start", "retrieval", "plan", "approval", "report", "end")));
    }

    private static Preset knowledgeReport() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("retrieval", "KNOWLEDGE_RETRIEVAL", 200, retrievalConfig()),
                node("draft", "LLM", 380, Map.of(
                        "temperature", 0.3,
                        "userPrompt", """
                                以下是从我的知识库检索到的片段：
                                {{ nodes.retrieval.output.documents }}

                                请写一份知识报告：先给 3 条要点，再按主题分组展开，
                                每个主题下列出来源笔记标题；没有资料支撑的主题不要写。""")),
                node("summary", "TEMPLATE", 580, Map.of(
                        "template", """
                                # 知识报告
                                **问题**：{{ input.question }}

                                {{ nodes.draft.output.text }}""")),
                node("end", "END", 760, Map.of()));
        return new Preset("knowledge-report", "知识报告生成",
                "检索知识库后生成带来源标题的报告，问题和正文一起渲染。实例化后先选知识库再发布。",
                new WorkflowGraph(SCHEMA_VERSION, nodes, chain("start", "retrieval", "draft", "summary", "end")));
    }

    /* ---------------- 小工具 ---------------- */

    private static Map<String, Object> retrievalConfig() {
        // kbIds 刻意留空：由用户在自己的知识库里挑，校验器会明确报「必须配置 kbIds」
        return Map.of("kbIds", List.of(), "topK", 6, "query", "{{ input.question }}", "mode", "hybrid");
    }

    private static WorkflowNode node(String id, String type, int x, Map<String, Object> config) {
        return new WorkflowNode(id, type, 1, new WorkflowNode.Position((double) x, 160.0), config);
    }

    private static List<WorkflowEdge> chain(String... ids) {
        List<WorkflowEdge> edges = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length - 1; i++) {
            edges.add(new WorkflowEdge("e-" + ids[i] + "-" + ids[i + 1], ids[i], ids[i + 1], null));
        }
        return List.copyOf(edges);
    }
}
