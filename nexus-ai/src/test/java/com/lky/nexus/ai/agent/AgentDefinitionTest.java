package com.lky.nexus.ai.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentDefinition} 的不可变性契约（Phase 4.1）。
 *
 * <p>这类"看起来只是 getter 的值对象"最容易在评审里被放过，但它的两个性质是有实际后果的：
 * <ol>
 *   <li><b>kbIds 不可变</b>：它会被塞进 {@code ToolContext} 跟着一次流式回答走完全程。
 *       若记录持有调用方传进来的那个可变 List，调用方事后 {@code add} 一个库，
 *       检索范围就会在飞行途中变化——这种 bug 完全不报错，只是"偶尔搜到了不该搜的库"；</li>
 *   <li><b>为空 = 不限制</b>：与 RAG 会话相反的语义（那边为空是硬错误）。搞反的后果是
 *       "以为收窄了范围，其实搜了全部"——正是这次改动的核心风险点，值得用测试钉住。</li>
 * </ol>
 */
@DisplayName("AgentDefinition：值对象的不可变性与 kbIds 语义")
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
    @DisplayName("agentId / code / name / systemPrompt / modelRole 缺失都在构造期就失败")
    void missingIdentityFieldsFailFast() {
        assertThatThrownBy(() -> new AgentDefinition(null, "c", "n", "p", "r", List.of(), false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentDefinition(1L, " ", "n", "p", "r", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
        assertThatThrownBy(() -> new AgentDefinition(1L, "c", "", "p", "r", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new AgentDefinition(1L, "c", "n", null, "r", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemPrompt");
        assertThatThrownBy(() -> new AgentDefinition(1L, "c", "n", "p", "\t", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelRole");
    }

    @Test
    @DisplayName("describe() 如实说明检索范围：绑了库说几个，没绑说「全部知识库」")
    void describeTellsTheScope() {
        assertThat(definition(List.of(1L, 2L)).describe()).isEqualTo("笔记助手(helper, 2 个知识库)");
        assertThat(definition(List.of()).describe()).isEqualTo("笔记助手(helper, 全部知识库)");
    }

    private AgentDefinition definition(List<Long> kbIds) {
        return new AgentDefinition(1L, "helper", "笔记助手", "只依据资料回答", "chat.agent", kbIds, false);
    }
}
