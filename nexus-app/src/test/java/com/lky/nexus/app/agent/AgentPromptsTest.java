package com.lky.nexus.app.agent;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.AnswerPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "回答策略只有一处"的守卫（4.4，decisions.md B.41）。
 *
 * <p>这些断言看着像在测字符串常量，其实钉的是一条设计约束：<b>"没有可引用资料时怎么办"
 * 只能有一个来源</b>。4.4 之前它在两处——{@code AGENT_SYSTEM} 的旧规则 2/3，以及
 * {@code KnowledgeSearchTool} 回灌给模型的那句话；两处一起把"没有资料"变成了"拒绝服务"，
 * 代价落在最该被照顾的新用户身上（第一次提问就吃拒绝）。
 *
 * <p>所以这里既钉"默认文本里没有那句话"，也钉"策略块恰好追加一次"——
 * 任何一处回潮都会让本类变红。
 */
@DisplayName("AgentPrompts：默认提示词策略中立、策略块只追加一次")
class AgentPromptsTest {

    @Test
    @DisplayName("默认提示词不含任何「没有资料时怎么办」的说法——那是策略块的事")
    void defaultPromptIsPolicyNeutral() {
        // 旧规则 2/3 那两句：把它们从默认文本里删掉、只留在策略块，正是这次修复的核心动作
        assertThat(AgentPrompts.AGENT_SYSTEM).doesNotContain("不要用你自己的既有知识");
        assertThat(AgentPrompts.AGENT_SYSTEM).doesNotContain("检索不到就如实说");
        // 但「反归属」必须留下：它才是原设计真正要守的那条（绝不把模型知识说成用户的笔记），
        // 与"没有资料时答不答"无关，因此不属于策略
        assertThat(AgentPrompts.AGENT_SYSTEM).contains("绝不要把你自己的知识说成是用户笔记里的内容");
    }

    @Test
    @DisplayName("策略块随 answerPolicy 追加，且恰好追加一次")
    void systemPromptAppendsExactlyOnePolicyBlock() {
        String preferred = AgentPrompts.systemPromptFor(definition(AnswerPolicy.KB_PREFERRED));
        String only = AgentPrompts.systemPromptFor(definition(AnswerPolicy.KB_ONLY));

        assertThat(preferred).startsWith("用户写的提示").contains(AgentPrompts.POLICY_KB_PREFERRED);
        assertThat(preferred).doesNotContain(AgentPrompts.POLICY_KB_ONLY);
        assertThat(only).contains(AgentPrompts.POLICY_KB_ONLY).doesNotContain(AgentPrompts.POLICY_KB_PREFERRED);
        // 出现两次就说明有第二个追加点——那正是本类要挡的形态
        assertThat(countOccurrences(preferred, AgentPrompts.POLICY_KB_PREFERRED)).isEqualTo(1);
    }

    @Test
    @DisplayName("两条策略的分界线是「归属」，不是「答不答」")
    void policiesDifferOnLabellingNotOnAnswering() {
        // KB_PREFERRED：允许用自身知识，但必须声明不是来自笔记
        assertThat(AgentPrompts.POLICY_KB_PREFERRED).contains("不是来自你的笔记");
        // KB_ONLY：不许用自身知识
        assertThat(AgentPrompts.POLICY_KB_ONLY).contains("不要用自己的知识作答");
    }

    @Test
    @DisplayName("旧默认文本与当前默认不同：否则存量升级的检测等于没写")
    void legacyTextDiffersFromCurrent() {
        assertThat(AgentPrompts.AGENT_SYSTEM_LEGACY.strip())
                .isNotEqualTo(AgentPrompts.AGENT_SYSTEM.strip());
        // 旧文本里必须还留着那两句，否则它认不出"这一行就是当初播种的老行"
        assertThat(AgentPrompts.AGENT_SYSTEM_LEGACY).contains("不要用你自己的既有知识回答");
        assertThat(AgentPrompts.AGENT_SYSTEM_LEGACY).contains("检索不到就如实说");
    }

    private AgentDefinition definition(AnswerPolicy policy) {
        return new AgentDefinition(1L, "helper", "笔记助手", "用户写的提示", "chat.agent",
                null, List.of(), List.of(), policy, false);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
