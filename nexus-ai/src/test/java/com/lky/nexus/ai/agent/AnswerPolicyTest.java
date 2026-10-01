package com.lky.nexus.ai.agent;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link AnswerPolicy} 的解析契约（4.4）。
 *
 * <p>为什么值得单独钉住"默认值是 KB_PREFERRED"：这个默认值决定<b>存量用户</b>的行为。
 * 旧代码只有一种行为（检索不到就拒答），把它设成默认，等于让所有人都默默继承一个
 * 已知会挡住新用户的问题；设成 KB_PREFERRED 才是"默认能服务、要严格的人显式选"。
 */
@DisplayName("AnswerPolicy：没有可引用资料时的回答策略")
class AnswerPolicyTest {

    @Test
    @DisplayName("默认是 KB_PREFERRED：旧行为（KB_ONLY）不该是默认值")
    void defaultIsKbPreferred() {
        assertThat(AnswerPolicy.DEFAULT).isEqualTo(AnswerPolicy.KB_PREFERRED);
    }

    @Test
    @DisplayName("null / 空白 / 大小写：都收敛成合法策略，不报错")
    void parseIsLenientAboutBlankAndCase() {
        assertThat(AnswerPolicy.parse(null)).isEqualTo(AnswerPolicy.DEFAULT);
        assertThat(AnswerPolicy.parse("   ")).isEqualTo(AnswerPolicy.DEFAULT);
        assertThat(AnswerPolicy.parse(" kb_only ")).isEqualTo(AnswerPolicy.KB_ONLY);
        assertThat(AnswerPolicy.parse("KB_PREFERRED")).isEqualTo(AnswerPolicy.KB_PREFERRED);
    }

    @Test
    @DisplayName("不认识的值抛 1001：在写入口就拦，不存下没人认识的值")
    void parseRejectsUnknownValue() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> AnswerPolicy.parse("FREE_STYLE"))
                .withMessageContaining("KB_PREFERRED")
                .satisfies(ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_INVALID.getCode()));
    }
}
