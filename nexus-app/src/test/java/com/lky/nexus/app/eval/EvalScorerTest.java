package com.lky.nexus.app.eval;

import com.lky.nexus.app.eval.EvalService.EvalCase;
import com.lky.nexus.app.eval.EvalService.EvalReport;
import com.lky.nexus.app.eval.EvalService.EvalResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测计分的纯单元测试（不依赖模型）：
 * 要点命中率、引用有效性、报告汇总这三件事必须确定性地正确，
 * 否则评测本身不可信——用"不可信的工具去度量质量"比没有度量更糟。
 */
class EvalScorerTest {

    @Test
    @DisplayName("要点全中 + 引用有效 → 通过，命中率 1.0")
    void fullHitPasses() {
        EvalResult result = EvalResult.of(
                new EvalCase("缓存击穿怎么解决？", List.of("SETNX", "逻辑过期")),
                "用 SETNX 加锁，也可以用逻辑过期方案。 [1]",
                List.of(), true, 2);

        assertThat(result.pointRecall()).isEqualTo(1.0);
        assertThat(EvalReport.of(List.of(result)).passed()).isEqualTo(1);
        assertThat(EvalReport.of(List.of(result)).avgPointRecall()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("漏掉一个要点 → 命中率 0.5，且不通过")
    void partialHitHalfRecall() {
        EvalResult result = EvalResult.of(
                new EvalCase("缓存击穿怎么解决？", List.of("SETNX", "逻辑过期")),
                "用 SETNX 加锁。 [1]",
                List.of("逻辑过期"), true, 1);

        assertThat(result.pointRecall()).isEqualTo(0.5);
        assertThat(EvalReport.of(List.of(result)).passed()).isZero();
    }

    @Test
    @DisplayName("要点全中但没有引用 → 要点维度满分、总评不通过（引用是防幻觉的一半）")
    void noCitationFailsEvenIfPointsHit() {
        EvalResult result = EvalResult.of(
                new EvalCase("JVM 用什么收集器？", List.of("G1")),
                "G1 按 Region 划分堆内存。",
                List.of(), false, 0);

        assertThat(result.pointRecall()).isEqualTo(1.0);
        assertThat(EvalReport.of(List.of(result)).passed()).isZero();
    }

    @Test
    @DisplayName("多用例汇总：平均命中率与通过数")
    void aggregatesMultipleCases() {
        EvalReport report = EvalReport.of(List.of(
                EvalResult.of(new EvalCase("Q1", List.of("A", "B")), "A 与 B 都在。 [1]", List.of(), true, 1),
                EvalResult.of(new EvalCase("Q2", List.of("C", "D")), "只说了 C。", List.of("D"), true, 0),
                EvalResult.of(new EvalCase("Q3", List.of("E")), "E 在引用里。 [1]", List.of(), true, 1)));

        assertThat(report.passed()).isEqualTo(2);
        assertThat(report.avgPointRecall()).isCloseTo((1.0 + 0.5 + 1.0) / 3.0, org.assertj.core.api.Assertions.within(0.0001));
    }
}