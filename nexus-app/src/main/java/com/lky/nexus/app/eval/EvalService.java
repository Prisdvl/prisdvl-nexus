package com.lky.nexus.app.eval;

import com.lky.nexus.app.agent.AgentAnswer;
import com.lky.nexus.app.agent.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库问答评测（Phase 6.1）：给"回答质量"一个可量化的说法。
 *
 * <p>评测<b>走 Agent 链路</b>（而不是 RAG 同步链路）：
 * Agent 的工具循环自带检索降级阶梯（混合 → SQL LIKE → 如实说明），
 * 因此评测在<b>任何环境</b>都能跑（standalone 无 ES 也能评）——
 * 这正是"端到端质量"该有的样子，而不是只评测"完整基础设施下的最优路径"。
 *
 * <p>评分两个维度：
 * <ul>
 *   <li><b>要点命中率</b>：回答是否包含每个预期要点（子串匹配）。
 *       为什么用子串：笔记里的关键术语（"SETNX"、"逻辑过期"）在回答中会原文出现，
 *       因此子串命中是确定性信号，不需要 LLM 判分（贵且不稳定）；</li>
 *   <li><b>引用有效性</b>：回答是否带来源标注（Agent 提示词要求"注明资料来源"，
 *       落地标记是"来源："，model 在降级路径下也会如实给）。</li>
 * </ul>
 * <b>诚实边界</b>：子串命中测"要点覆盖"，不测"语言质量/条理性"——那种维度
 * 需要 LLM-as-judge，另做。先让最硬的两个指标可测、可回归。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final AgentService agentService;

    public EvalService(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 一个评测用例：问题 + 预期出现的要点（笔记中会原文出现的术语/短语）。 */
    public record EvalCase(String question, List<String> expectedPoints) {
    }

    /** 单条结果：回答原文 + 缺失要点 + 引用/检索情况。pointRecall 作为组件，保证 JSON 可读。 */
    public record EvalResult(EvalCase testCase, String answer, List<String> missingPoints,
                             boolean citationsValid, int citationCount, double pointRecall) {

        public static EvalResult of(EvalCase testCase, String answer, List<String> missingPoints,
                                    boolean citationsValid, int citationCount) {
            int total = testCase.expectedPoints().size();
            double recall = total == 0 ? 1.0 : (total - missingPoints.size()) / (double) total;
            return new EvalResult(testCase, answer, missingPoints, citationsValid, citationCount, recall);
        }
    }

    /** 报告：逐条结果 + 汇总（汇总指标也是组件，随 JSON 返回）。 */
    public record EvalReport(List<EvalResult> results, double avgPointRecall, long passed) {

        public static EvalReport of(List<EvalResult> results) {
            if (results.isEmpty()) {
                return new EvalReport(results, 0.0, 0);
            }
            double sum = 0.0;
            long passedCount = 0;
            for (EvalResult result : results) {
                sum += result.pointRecall();
                if (result.pointRecall() == 1.0 && result.citationsValid()) {
                    passedCount++;
                }
            }
            return new EvalReport(results, sum / results.size(), passedCount);
        }
    }

    public EvalReport run(Long userId, Long knowledgeBaseId, List<EvalCase> cases) {
        List<EvalResult> results = new ArrayList<>(cases.size());
        for (EvalCase testCase : cases) {
            AgentAnswer answer = agentService.ask(userId, testCase.question());
            List<String> missing = testCase.expectedPoints().stream()
                    .filter(point -> !answer.answer().contains(point))
                    .toList();
            // Agent 回答的落地标记：是否注明了资料来源（降级路径也会如实标注）
            boolean citationsValid = answer.answer().contains("来源");
            results.add(EvalResult.of(testCase, answer.answer(), missing, citationsValid,
                    answer.toolCalls().size()));
        }
        EvalReport report = EvalReport.of(results);
        log.info("评测完成：{} 条用例，平均要点命中率 {}，通过 {} 条",
                results.size(), report.avgPointRecall(), report.passed());
        return report;
    }
}