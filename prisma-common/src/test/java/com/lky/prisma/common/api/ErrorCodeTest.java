package com.lky.prisma.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码规范测试。
 *
 * <p>错误码一旦被前端/文档/评测引用就相当于对外契约，所以用测试把它锁住：
 * 唯一性、分段归属、未知码兜底，都不允许在后续迭代中被无意破坏。
 */
class ErrorCodeTest {

    @Test
    @DisplayName("错误码数值全局唯一")
    void codesAreUnique() {
        Map<Integer, ErrorCode> seen = new HashMap<>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            ErrorCode previous = seen.put(errorCode.getCode(), errorCode);
            assertThat(previous)
                    .as("错误码 %d 被 %s 与 %s 重复使用", errorCode.getCode(), previous, errorCode)
                    .isNull();
        }
    }

    @Test
    @DisplayName("成功码固定为 0")
    void successCodeIsZero() {
        assertThat(ErrorCode.SUCCESS.getCode()).isZero();
        assertThat(ErrorCode.SUCCESS.getMessage()).isEqualTo("success");
    }

    @Test
    @DisplayName("错误码落在约定的分段区间内")
    void codesFollowSegmentConvention() {
        assertThat(ErrorCode.PARAM_INVALID.getCode()).isBetween(1000, 1999);
        assertThat(ErrorCode.UNAUTHENTICATED.getCode()).isBetween(2000, 2999);
        assertThat(ErrorCode.DOCUMENT_NOT_FOUND.getCode()).isBetween(3000, 3999);
        // 关键决策：AI 模型类错误统一在 4xxx（不是需求示例里的 5001）
        assertThat(ErrorCode.MODEL_UNAVAILABLE.getCode()).isBetween(4000, 4999);
        assertThat(ErrorCode.TOOL_EXECUTION_FAILED.getCode()).isBetween(5000, 5999);
        assertThat(ErrorCode.WORKFLOW_NODE_FAILED.getCode()).isBetween(6000, 6999);
        assertThat(ErrorCode.RATE_LIMITED.getCode()).isBetween(7000, 7999);
    }

    @Test
    @DisplayName("fromCode：已知码精确反查，未知码兜底为 INTERNAL_ERROR（不抛异常）")
    void fromCodeResolvesOrFallsBack() {
        assertThat(ErrorCode.fromCode(0)).isEqualTo(ErrorCode.SUCCESS);
        assertThat(ErrorCode.fromCode(3001)).isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        assertThat(ErrorCode.fromCode(4002)).isEqualTo(ErrorCode.MODEL_TIMEOUT);
        assertThat(ErrorCode.fromCode(123456)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("Result 工厂方法：成功/失败形态正确")
    void resultFactoriesBehaveAsExpected() {
        Result<String> ok = Result.ok("data");
        assertThat(ok.code()).isZero();
        assertThat(ok.data()).isEqualTo("data");
        assertThat(Result.isSuccess(ok)).isTrue();

        Result<String> fail = Result.fail(ErrorCode.MODEL_UNAVAILABLE);
        assertThat(fail.code()).isEqualTo(4001);
        assertThat(fail.message()).isEqualTo("AI 模型不可用");
        assertThat(fail.data()).isNull();
        assertThat(Result.isSuccess(fail)).isFalse();
        assertThat(Result.isSuccess(null)).isFalse();
    }
}
