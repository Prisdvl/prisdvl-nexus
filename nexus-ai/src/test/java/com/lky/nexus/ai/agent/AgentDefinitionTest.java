package com.lky.nexus.ai.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentDefinition} 的不可变性契约（Phase 4.1；4.3 补 toolCodes；5.4 补 temperature）。
 *
 * <p>这类"看起来只是 getter 的值对象"最容易在评审里被放过，但它的性质是有实际后果的：
 * <ol>
 *   <li><b>kbIds 不可变</b>：它会被塞进 {@code ToolContext} 跟着一次流式回答走完全程。
 *       若记录持有调用方传进来的那个可变 List，调用方事后 {@code add} 一个库，
 *       检索范围就会在飞行途中变化——这种 bug 完全不报错，只是"偶尔搜到了不该搜的库"；</li>
 *   <li><b>为空 = 不限制</b>：与 RAG 会话相反的语义（那边为空是硬错误）。搞反的后果是
 *       "以为收窄了范围，其实搜了全部"——正是这次改动的核心风险点，值得用测试钉住。
 *       {@code toolCodes} 沿用同一约定（4.3）：为空 = 装该用户全部启用中的工具，
 *       而<b>不是</b>"一个工具都不装"；</li>
 *   <li><b>toolCodes 同样防御性拷贝</b>：它会在装配工具时被读，改到飞行中的白名单
 *       表现就是"这次回答多了一个工具"，同样不报错。</li>
 * </ol>
 */
@DisplayName("AgentDefinition：值对象的不可变性与 kbIds / toolCodes 语义")
class AgentDefinitionTest {

    @Test
    @DisplayName("kbIds 防御性拷贝：构造后改原列表，定义里的范围不变")
    void kbIdsAreCopiedNotAliased() {
        List<Long> mutable = new ArrayList<>(List.of(1L, 2L));
        AgentDefinition definition = definition(mutable);

        mutable.add(3L);

        assertThat(definition.kbIds()).containsExactly(1L, 2L);
        assertThat(definition.scoped()).isTrue();
    }

    @Test
    @DisplayName("toolCodes 防御性拷贝 + 去重保序：绑两次同一个工具不是「绑了 2 个工具」")
    void toolCodesAreCopiedAndDeduplicated() {
        List<String> mutable = new ArrayList<>(List.of("knowledge_search", "calculator", "knowledge_search"));
        AgentDefinition definition = definition(List.of(), mutable);

        mutable.add("calculator");

        assertThat(definition.toolCodes()).containsExactly("knowledge_search", "calculator");
        assertThat(definition.toolScoped()).isTrue();
    }

    @Test
    @DisplayName("空 toolCodes = 不限制（装全部启用中的工具），不是「一个都不装」")
    void emptyToolCodesMeansUnrestricted() {
        assertThat(definition(List.of(), List.of()).toolScoped()).isFalse();
        assertThat(definition(List.of(), List.of()).toolCodes()).isEmpty();
    }

    @Test
    @DisplayName("toolCodes 为 null 会被拒绝：null 与「不限制」是两件事")
    void nullToolCodesIsRejected() {
        assertThatThrownBy(() -> definition(List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toolCodes");
    }

    @Test
    @DisplayName("kbIds 去重保序：绑两次同一个库不会让「绑了 2 个库」变成错觉")
    void kbIdsAreDeduplicatedKeepingOrder() {
        AgentDefinition definition = definition(new ArrayList<>(List.of(7L, 3L, 7L)));

        assertThat(definition.kbIds()).containsExactly(7L, 3L);
    }

    @Test
    @DisplayName("空 kbIds = 不限制（与 RAG 会话语义相反，这里必须显式为 false）")
    void emptyKbIdsMeansUnrestricted() {
        assertThat(definition(List.of()).scoped()).isFalse();
        assertThat(definition(List.of()).kbIds()).isEmpty();
    }

    @Test
    @DisplayName("kbIds 为 null 会被拒绝：null 与「不限制」是两件事，不能靠 null 表达")
    void nullKbIdsIsRejected() {
        assertThatThrownBy(() -> definition(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kbIds");
    }

    @Test
    @DisplayName("agentId / code / name / systemPrompt / modelRole / answerPolicy 缺失都在构造期就失败")
    void missingIdentityFieldsFailFast() {
        assertThatThrownBy(() -> new AgentDefinition(null, "c", "n", "p", "r", null, List.of(), List.of(),
                AnswerPolicy.KB_PREFERRED, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentDefinition(1L, " ", "n", "p", "r", null, List.of(), List.of(),
                AnswerPolicy.KB_PREFERRED, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
        assertThatThrownBy(() -> new AgentDefinition(1L, "c", "", "p", "r", null, List.of(), List.of(),
                AnswerPolicy.KB_PREFERRED, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new AgentDefinition(1L, "c", "n", null, "r", null, List.of(), List.of(),
                AnswerPolicy.KB_PREFERRED, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemPrompt");
        assertThatThrownBy(() -> new AgentDefinition(1L, "c", "n", "p", "\t", null, List.of(), List.of(),
                AnswerPolicy.KB_PREFERRED, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelRole");
        // 回答策略必填：装配阶段就该定好，运行期不做兜底（否则"没配时用什么"会多出第二个决定的地方）
        assertThatThrownBy(() -> new AgentDefinition(1L, "c", "n", "p", "r", null, List.of(), List.of(), null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("answerPolicy");
    }

    @Test
    @DisplayName("temperature 可空（null = 跟随全局默认），且 0 不被当成「没配」")
    void temperatureIsNullableAndZeroIsMeaningful() {
        // null 与 0 是两件事：前者"没配过"，后者"显式要求最高确定性"。
        // 若哪天有人把 null 收敛成 0，这条会红——那正是要拦住的那种"顺手补个默认值"
        assertThat(definition(List.of()).temperature()).isNull();

        AgentDefinition greedy = new AgentDefinition(1L, "helper", "笔记助手", "提示", "chat.agent",
                0.0, List.of(), List.of(), AnswerPolicy.KB_PREFERRED, false);
        assertThat(greedy.temperature()).isZero();
    }

    @Test
    @DisplayName("describe() 不掺温度：它讲的是「范围有多宽」，温度不是范围")
    void describeStaysAboutScope() {
        AgentDefinition hot = new AgentDefinition(1L, "helper", "笔记助手", "提示", "chat.agent",
                1.8, List.of(1L), List.of(), AnswerPolicy.KB_PREFERRED, false);
        // 日志里那句话的读法是"哪个 Agent、看几个库、用几个工具"，
        // 多一个温度会让已有日志的解析方式全部失效
        assertThat(hot.describe()).isEqualTo("笔记助手(helper, 1 个知识库, 全部工具)");
    }

    @Test
    @DisplayName("describe() 如实说明检索范围与工具白名单：绑了说几个，没绑说「全部」")
    void describeTellsTheScope() {
        assertThat(definition(List.of(1L, 2L)).describe())
                .isEqualTo("笔记助手(helper, 2 个知识库, 全部工具)");
        assertThat(definition(List.of()).describe())
                .isEqualTo("笔记助手(helper, 全部知识库, 全部工具)");
        // 白名单窄的时候，日志里这句话就是"这次为什么只有一个工具"的唯一线索
        assertThat(definition(List.of(), List.of("calculator")).describe())
                .isEqualTo("笔记助手(helper, 全部知识库, 1 个工具)");
    }

    private AgentDefinition definition(List<Long> kbIds) {
        return definition(kbIds, List.of());
    }

    private AgentDefinition definition(List<Long> kbIds, List<String> toolCodes) {
        return new AgentDefinition(1L, "helper", "笔记助手", "只依据资料回答", "chat.agent",
                null, kbIds, toolCodes, AnswerPolicy.KB_PREFERRED, false);
    }
}
