package com.lky.nexus.ai.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型引用解析测试。
 *
 * <p>重点是那个真实的坑：Ollama 的模型名自带冒号（{@code qwen3:7b}），
 * 必须按第一个冒号切分，否则模型名会被截断。
 */
class ModelRefTest {

    @Test
    @DisplayName("标准引用：provider:model")
    void parsesSimpleReference() {
        ModelRef ref = ModelRef.parse("deepseek:deepseek-chat");
        assertThat(ref.provider()).isEqualTo("deepseek");
        assertThat(ref.model()).isEqualTo("deepseek-chat");
        assertThat(ref.value()).isEqualTo("deepseek:deepseek-chat");
    }

    @Test
    @DisplayName("模型名含冒号（Ollama 的 qwen3:7b）只切第一个冒号")
    void keepsColonInsideModelName() {
        ModelRef ref = ModelRef.parse("ollama:qwen3:7b");
        assertThat(ref.provider()).isEqualTo("ollama");
        assertThat(ref.model()).isEqualTo("qwen3:7b");
        assertThat(ref.value()).isEqualTo("ollama:qwen3:7b");
    }

    @Test
    @DisplayName("非法引用抛 4006（属于配置问题，与用户输入无关）")
    void rejectsInvalidReferences() {
        for (String invalid : new String[]{"deepseek", ":deepseek-chat", "deepseek:", "", "  "}) {
            assertThatThrownBy(() -> ModelRef.parse(invalid))
                    .as("引用 [%s] 应当被拒绝", invalid)
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getCode())
                    .isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
        }
    }

    @Test
    @DisplayName("null 引用抛 4006，而不是 NPE")
    void rejectsNull() {
        assertThatThrownBy(() -> ModelRef.parse(null))
                .isInstanceOf(BusinessException.class);
    }
}
