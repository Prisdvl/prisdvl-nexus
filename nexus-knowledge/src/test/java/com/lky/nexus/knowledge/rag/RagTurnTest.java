package com.lky.nexus.knowledge.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史轮次的构造规则（Phase 3.4 从两个调用方里收敛出来的共享逻辑）。
 *
 * <p>为什么要单独测：这条规则<b>同时决定</b>两条链路的上下文——
 * 知识库问答把前端回传的历史交给它，会话流式把 ChatMemory 里的历史交给它。
 * 一旦它的行为变了（比如从"取最后 3 轮"退回"取最早 3 轮"），
 * 表现不会是报错，而是"追问时答非所问"，而且只在**多轮**之后才出现，最难复现。
 */
class RagTurnTest {

    @Test
    @DisplayName("问题为空白 → 不是一轮历史（返回 null，由调用方过滤）")
    void blankQuestionIsNotATurn() {
        assertThat(RagAnswerService.RagTurn.of(null, "答")).isNull();
        assertThat(RagAnswerService.RagTurn.of("   ", "答")).isNull();
    }

    @Test
    @DisplayName("问题两端空白被去掉；回答只保留开头一段（避免历史挤掉本轮资料）")
    void questionTrimmedAndAnswerSummarized() {
        String longAnswer = "字".repeat(RagAnswerService.RagTurn.SUMMARY_LENGTH + 50);

        RagAnswerService.RagTurn turn = RagAnswerService.RagTurn.of("  缓存击穿怎么解决？ ", longAnswer);

        assertThat(turn.question()).isEqualTo("缓存击穿怎么解决？");
        assertThat(turn.answerSummary())
                .hasSize(RagAnswerService.RagTurn.SUMMARY_LENGTH + 1)
                .endsWith("…");
    }

    @Test
    @DisplayName("回答里的换行被压平：多行回答直接拼进提示词会破坏【资料】/【问题】的块结构")
    void answerIsFlattened() {
        RagAnswerService.RagTurn turn = RagAnswerService.RagTurn.of("问", "第一行\n\n  第二行  ");

        assertThat(turn.answerSummary()).isEqualTo("第一行 第二行");
    }

    @Test
    @DisplayName("空回答 → summary 为 null（历史里只有问题，不写一个空的\"答：\"）")
    void blankAnswerBecomesNullSummary() {
        assertThat(RagAnswerService.RagTurn.of("问", null).answerSummary()).isNull();
        assertThat(RagAnswerService.RagTurn.of("问", "  ").answerSummary()).isNull();
    }

    @Test
    @DisplayName("最多取最后 3 轮：最近几轮才有助于消解指代")
    void lastTurnsKeepsTheMostRecent() {
        List<RagAnswerService.RagTurn> turns = Arrays.asList(
                RagAnswerService.RagTurn.of("第1轮", "a"),
                RagAnswerService.RagTurn.of("第2轮", "b"),
                RagAnswerService.RagTurn.of("第3轮", "c"),
                RagAnswerService.RagTurn.of("第4轮", "d"));

        assertThat(RagAnswerService.RagTurn.lastTurns(turns))
                .extracting(RagAnswerService.RagTurn::question)
                .containsExactly("第2轮", "第3轮", "第4轮");
    }

    @Test
    @DisplayName("null 元素与空列表都被安全处理（历史来自数据库/记忆，形态不受控）")
    void nullsAreDropped() {
        assertThat(RagAnswerService.RagTurn.lastTurns(null)).isEmpty();
        assertThat(RagAnswerService.RagTurn.lastTurns(List.of())).isEmpty();
        assertThat(RagAnswerService.RagTurn.lastTurns(Arrays.asList(null, RagAnswerService.RagTurn.of("问", "答"))))
                .hasSize(1);
    }
}
