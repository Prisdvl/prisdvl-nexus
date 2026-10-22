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
 * <p><b>与业界模板（n8n / Dify / Coze）的对应关系</b>：那几家的模板库大体分四类——
 * 知识问答（检索 → 生成 → 无资料兜底）、内容生产（生成 → 排版 → 输出）、
 * 分流与运营（分类 → 条件分支 → 不同处理）、审核与合规（生成 → 人工闸门 → 发布）。
 * 下面每个模板都落在其中一类，并在 {@code category} 里标注，前端按类分组展示。
 * 这样用户不必从空白画布开始猜节点怎么连。
 *
 * <p><b>与设计稿的差异要明说</b>：`architecture.md` §12 / `roadmap` 8.5 画的那套流程依赖
 * `PARALLEL` 与 `TOOL` 节点，而实现里的枚举只有
 * `START / KNOWLEDGE_RETRIEVAL / LLM / AGENT / IF_ELSE / TEMPLATE / HUMAN_APPROVAL / END`。
 * 所以这里给的是用现有节点写出的**顺序版**：检索 → 模型 → 渲染；条件分支走 IF_ELSE 的
 * true / false 两个端口。「7 天学习计划」「对外发布审核」额外插一个人工审批闸门。
 * 没有并行扇出、没有工具节点——这不是偷懒，是不想为了模板去虚构节点类型。
 *
 * <p>模板只是<b>脚手架</b>：`KNOWLEDGE_RETRIEVAL` 的 `kbIds` 刻意留空（校验器要求非空），
 * 所以实例化出来的工作流在用户挑好知识库之前是「结构完整但校验不通过」——这是预期行为，
 * 由 validate 明确告诉他缺什么，而不是偷偷塞一个假知识库。
 */
@Component
public class WorkflowPresets {

    private static final int SCHEMA_VERSION = 1;

    /** 模板分类：前端按它分组展示，顺序固定。 */
    public static final String CAT_QA = "qa";
    public static final String CAT_CONTENT = "content";
    public static final String CAT_OPS = "ops";
    public static final String CAT_REVIEW = "review";

    /**
     * 一个模板：code 稳定（文档与前端引用它），图在实例化时才写成某个用户的草稿。
     *
     * @param category 分类 code（qa / content / ops / review）
     * @param tags     一句话看点，前端在卡片上以 chip 展示
     */
    public record Preset(String code, String name, String category, String description,
                         List<String> tags, WorkflowGraph graph) {
    }

    private final List<Preset> presets;

    public WorkflowPresets() {
        this.presets = List.of(
                knowledgeGapAnalysis(),
                kbQaBot(),
                studyPlan7d(),
                knowledgeReport(),
                weeklyReport(),
                meetingNotes(),
                supportTriage(),
                releaseReview());
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

    /* ---------------- 知识问答类 ---------------- */

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
        return new Preset("knowledge-gap-analysis", "知识缺口分析", CAT_QA,
                "检索知识库后让模型给出覆盖度与缺口清单，每条结论要求附证据。实例化后先选知识库再发布。",
                List.of("检索增强", "覆盖度评估", "要求附证据"),
                new WorkflowGraph(SCHEMA_VERSION, nodes, chain("start", "retrieval", "analysis", "report", "end")));
    }

    /** 知识问答：检索到资料才答，没资料走兜底文案——这是各家「知识库问答机器人」模板的骨架。 */
    private static Preset kbQaBot() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("retrieval", "KNOWLEDGE_RETRIEVAL", 200, retrievalConfig()),
                node("has_docs", "IF_ELSE", 360, Map.of(
                        "operator", "NE",
                        "left", "{{ nodes.retrieval.output.documents }}",
                        "right", "")),
                node("answer", "LLM", 520, Map.of(
                        "temperature", 0.2,
                        "userPrompt", """
                                你是知识库问答助手。只根据下面的片段回答，不要补充片段以外的知识：
                                {{ nodes.retrieval.output.documents }}

                                问题：{{ input.question }}

                                回答时给出结论，并在末尾用「依据：」列出引用了哪几个片段标题。
                                片段不足以回答时，直说资料里没有，不要猜。""")),
                node("fallback", "TEMPLATE", 520, Map.of(
                        "template", """
                                ## 没有检索到相关资料

                                关于「{{ input.question }}」，当前知识库里没有匹配的片段。

                                可以试试：
                                - 换一种更具体的问法；
                                - 把相关文档导入知识库并重新建索引。""")),
                node("end", "END", 720, Map.of()));
        List<WorkflowEdge> edges = List.of(
                edge("start", "retrieval"),
                edge("retrieval", "has_docs"),
                branchEdge("has_docs", "answer", "true"),
                branchEdge("has_docs", "fallback", "false"),
                edge("answer", "end"),
                edge("fallback", "end"));
        return new Preset("kb-qa-bot", "知识库问答机器人", CAT_QA,
                "检索命中就带依据作答，没命中走兜底文案，避免模型凭空编造。实例化后先选知识库再发布。",
                List.of("检索增强", "条件分支", "兜底话术"),
                new WorkflowGraph(SCHEMA_VERSION, nodes, edges));
    }

    /* ---------------- 内容生产类 ---------------- */

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
        return new Preset("study-plan-7d", "7 天学习计划", CAT_CONTENT,
                "检索笔记后生成 7 天学习计划，并在渲染前插入一道人工审批闸门。实例化后先选知识库再发布。",
                List.of("学习规划", "人工审批", "基于笔记"),
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
        return new Preset("knowledge-report", "知识报告生成", CAT_CONTENT,
                "检索知识库后生成带来源标题的报告，问题和正文一起渲染。实例化后先选知识库再发布。",
                List.of("报告", "来源标题", "可直接分享"),
                new WorkflowGraph(SCHEMA_VERSION, nodes, chain("start", "retrieval", "draft", "summary", "end")));
    }

    /** 周报：纯生成型模板，不依赖知识库——把素材粘进 input.materials 就能跑。 */
    private static Preset weeklyReport() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("draft", "LLM", 220, Map.of(
                        "temperature", 0.35,
                        "userPrompt", """
                                你是一位技术负责人。根据下面这周的工作素材写一份周报：
                                {{ input.materials }}

                                结构：本周完成（按项目分组，每条带影响面）→ 进行中（带进度与卡点）→ 下周计划。
                                只写素材里能支撑的内容，不确定的地方标「待确认」。""")),
                node("format", "TEMPLATE", 420, Map.of(
                        "template", """
                                # 本周工作周报

                                > 主题：{{ input.topic }}

                                {{ nodes.draft.output.text }}""")),
                node("end", "END", 600, Map.of()));
        return new Preset("weekly-report", "周报生成", CAT_CONTENT,
                "把本周素材丢给模型，按「完成 / 进行中 / 下周计划」三段式成稿，不需要知识库。",
                List.of("无需知识库", "三段式", "可直接发"),
                new WorkflowGraph(SCHEMA_VERSION, nodes, chain("start", "draft", "format", "end")));
    }

    private static Preset meetingNotes() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("extract", "LLM", 220, Map.of(
                        "temperature", 0.2,
                        "userPrompt", """
                                把下面的会议记录整理成结构化纪要：
                                {{ input.transcript }}

                                输出四块：议题 / 结论（每条标出拍板人）/ 待办（负责人 + 截止时间）/ 遗留问题。
                                记录里没提到的负责人、时间不要编，写「未指定」。""")),
                node("format", "TEMPLATE", 420, Map.of(
                        "template", """
                                # 会议纪要

                                **会议**：{{ input.title }}

                                {{ nodes.extract.output.text }}""")),
                node("end", "END", 600, Map.of()));
        return new Preset("meeting-notes", "会议纪要结构化", CAT_CONTENT,
                "把零散会议记录整理成议题 / 结论 / 待办 / 遗留问题四块，缺信息处标注而不是编造。",
                List.of("无需知识库", "待办提取", "不编造"),
                new WorkflowGraph(SCHEMA_VERSION, nodes, chain("start", "extract", "format", "end")));
    }

    /* ---------------- 分流与运营 / 审核 ---------------- */

    /** 工单分流：先让模型归类，再用 IF_ELSE 走两条不同话术——Dify / n8n 里最常见的「分类 → 分支」形态。 */
    private static Preset supportTriage() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("classify", "LLM", 220, Map.of(
                        "temperature", 0,
                        "userPrompt", """
                                判断下面这条工单属于哪一类，只输出类别词，不要解释：
                                - 退款：涉及退款、发票、重复扣费
                                - 技术：无法登录、报错、功能异常
                                - 其他：其余情况

                                工单内容：{{ input.ticket }}""")),
                node("is_refund", "IF_ELSE", 400, Map.of(
                        "operator", "CONTAINS",
                        "left", "{{ nodes.classify.output.text }}",
                        "right", "退款")),
                node("refund", "TEMPLATE", 560, Map.of(
                        "template", """
                                ## 退款工单已受理

                                我们已收到：{{ input.ticket }}

                                处理流程：核对订单 → 财务审核 → 原路退回（1–3 个工作日）。
                                补充材料请在会话里直接回复，无需重复提工单。""")),
                node("handoff", "TEMPLATE", 560, Map.of(
                        "template", """
                                ## 工单已转人工

                                我们已收到：{{ input.ticket }}

                                这条不属于退款类，已转给对应值班同学，预计 2 小时内响应。
                                紧急问题可在会话里回复「加急」。""")),
                node("end", "END", 740, Map.of()));
        List<WorkflowEdge> edges = List.of(
                edge("start", "classify"),
                edge("classify", "is_refund"),
                branchEdge("is_refund", "refund", "true"),
                branchEdge("is_refund", "handoff", "false"),
                edge("refund", "end"),
                edge("handoff", "end"));
        return new Preset("support-triage", "工单分流", CAT_OPS,
                "模型先归类，再用条件分支走「退款流程 / 转人工」两套话术，不需要知识库。",
                List.of("条件分支", "自动分类", "两套话术"),
                new WorkflowGraph(SCHEMA_VERSION, nodes, edges));
    }

    private static Preset releaseReview() {
        List<WorkflowNode> nodes = List.of(
                node("start", "START", 40, Map.of()),
                node("draft", "LLM", 220, Map.of(
                        "temperature", 0.4,
                        "userPrompt", """
                                根据下面的要点写一段对外发布文案（面向非技术读者，不超过 200 字）：
                                {{ input.brief }}

                                不要夸张形容词，不要承诺未实现的能力。""")),
                // 对外发布的文字必须有人看过：闸门放在渲染之前，拒绝即本次运行失败
                node("gate", "HUMAN_APPROVAL", 420, Map.of()),
                node("publish", "TEMPLATE", 600, Map.of(
                        "template", """
                                # 发布文案（已人工确认）

                                {{ nodes.draft.output.text }}""")),
                node("end", "END", 760, Map.of()));
        return new Preset("release-review", "对外文案审核", CAT_REVIEW,
                "先生成对外文案，再卡一道人工审批；拒绝则本次运行失败，不会带病发布。",
                List.of("人工审批", "对外发布", "可驳回"),
                new WorkflowGraph(SCHEMA_VERSION, nodes, chain("start", "draft", "gate", "publish", "end")));
    }

    /* ---------------- 小工具 ---------------- */

    private static Map<String, Object> retrievalConfig() {
        // kbIds 刻意留空：由用户在自己的知识库里挑，校验器会明确报「必须配置 kbIds」
        return Map.of("kbIds", List.of(), "topK", 6, "query", "{{ input.question }}", "mode", "hybrid");
    }

    private static WorkflowNode node(String id, String type, int x, Map<String, Object> config) {
        return new WorkflowNode(id, type, 1, new WorkflowNode.Position((double) x, 160.0), config);
    }

    private static WorkflowEdge edge(String from, String to) {
        return new WorkflowEdge("e-" + from + "-" + to, from, to, null);
    }

    private static WorkflowEdge branchEdge(String from, String to, String handle) {
        return new WorkflowEdge("e-" + from + "-" + to + "-" + handle, from, to, handle);
    }

    private static List<WorkflowEdge> chain(String... ids) {
        List<WorkflowEdge> edges = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length - 1; i++) {
            edges.add(edge(ids[i], ids[i + 1]));
        }
        return List.copyOf(edges);
    }
}
