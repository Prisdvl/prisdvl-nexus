package com.lky.nexus.ai.error;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败归因的判定表（Phase 5.2 / P1-10、P1-13）。
 *
 * <p>为什么这张表值得单独测：修这条之前，<b>所有</b>流式失败都报 {@code 4001 "模型调用失败"}。
 * 用户撞到工具轮数上限时会去换模型、查额度，而正确答案是"把问题拆小"。
 * 归因错了不会让程序崩，只会让所有人（用户、支持、将来的自己）沿着错误的方向排查——
 * 这类"错报"比崩溃更难发现，因此每一个分支都要有一条用例钉住。
 *
 * <p>另一条不变量：<b>判定必须能穿透包装</b>。框架与 HTTP 客户端都会层层包异常
 * （{@code RetryExhaustedException → WebClientRequestException → ConnectException}），
 * 只看最外层就等于永远归到默认桶里。
 */
@DisplayName("FailureClassifier：谁出的错，报谁的码")
class FailureClassifierTest {

    @Test
    @DisplayName("工具轮数上限（单工具）→ 5002，如实带上工具名与上限，且不可重试")
    void perToolLimitIsReportedAs5002() {
        ToolCallLimitExceededException error =
                new ToolCallLimitExceededException("knowledge_search", 40, null);

        FailureClassifier.Failure failure = FailureClassifier.classify(error);

        assertThat(failure.code()).isEqualTo(ErrorCode.AGENT_MAX_ITERATIONS_EXCEEDED);
        assertThat(failure.message()).contains("knowledge_search").contains("40");
        // 重试同样的问法必然再撞一次（上限是确定性的），retryable 说 true 就是骗用户
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    @DisplayName("工具轮数上限（合计，toolName 为空）→ 5002，文案不必提工具名")
    void totalLimitIsReportedAs5002WithoutToolName() {
        ToolCallLimitExceededException error =
                new ToolCallLimitExceededException(null, 150, null);

        FailureClassifier.Failure failure = FailureClassifier.classify(error);

        assertThat(failure.code()).isEqualTo(ErrorCode.AGENT_MAX_ITERATIONS_EXCEEDED);
        assertThat(failure.message()).contains("150").contains("总次数");
    }

    /** 借用真机栈上的类名（{@code com.openai.errors.UnauthorizedException}）；判定只看名字，不看类型。 */
    static class UnauthorizedException extends RuntimeException {
        UnauthorizedException(String message) {
            super(message);
        }
    }

    @Test
    @DisplayName("业务异常原样保留自己的码与文案（RAG 域、权限域不能被吞成 4001）")
    void businessExceptionKeepsItsOwnCode() {
        BusinessException vectorStoreDown =
                new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE, "向量库连接失败，检索不可用");

        FailureClassifier.Failure failure = FailureClassifier.classify(vectorStoreDown);

        assertThat(failure.code()).isEqualTo(ErrorCode.VECTOR_STORE_UNAVAILABLE);
        assertThat(failure.message()).isEqualTo("向量库连接失败，检索不可用");
        // 下游服务不可用属于"过一会儿可能就好了"
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    @DisplayName("业务异常里不可重试的那类（角色未配置 4006）retryable=false")
    void configurationErrorsAreNotRetryable() {
        FailureClassifier.Failure failure = FailureClassifier.classify(
                new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED, "模型 xxx 未注册"));

        assertThat(failure.code()).isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED);
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    @DisplayName("上游拒绝密钥 → 4007 且明确不可重试（'请稍后重试'在这里是骗人的建议）")
    void credentialRejectionIsReportedAs4007() {
        FailureClassifier.Failure failure = FailureClassifier.classify(
                new UnauthorizedException("401: Invalid API key."));

        assertThat(failure.code()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
        assertThat(failure.message()).isEqualTo(FailureClassifier.MODEL_AUTH_FAILED_MESSAGE);
        assertThat(failure.retryable()).isFalse();

        // 被 SDK/重试层包起来也要认得出来（真机上就是这样的栈）
        assertThat(FailureClassifier.classify(new RuntimeException("call failed",
                new UnauthorizedException("401"))).code()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
    }

    @Test
    @DisplayName("业务异常优先于类名匹配：FORBIDDEN 不会被说成'模型密钥过期'")
    void businessExceptionWinsOverNameMatching() {
        // 顺序本身就是契约：类名匹配是"猜"，业务异常是"已经判定过的事实"，猜不能覆盖事实
        FailureClassifier.Failure failure = FailureClassifier.classify(
                new BusinessException(ErrorCode.FORBIDDEN, "无权限访问该资源", new UnauthorizedException("403")));

        assertThat(failure.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(failure.message()).isEqualTo("无权限访问该资源");
    }

    @Test
    @DisplayName("超时（含被包装的）→ 4002 + 可重试")
    void timeoutIsReportedAs4002() {
        assertThat(FailureClassifier.classify(new SocketTimeoutException("read timed out")).code())
                .isEqualTo(ErrorCode.MODEL_TIMEOUT);
        assertThat(FailureClassifier.classify(new TimeoutException("timeout")).code())
                .isEqualTo(ErrorCode.MODEL_TIMEOUT);

        // 包装两层：真实栈上就是 RetryExhausted → WebClientRequest → SocketTimeout
        RuntimeException wrapped = new RuntimeException("retry exhausted",
                new IllegalStateException("request failed",
                        new SocketTimeoutException("read timed out")));
        FailureClassifier.Failure failure = FailureClassifier.classify(wrapped);
        assertThat(failure.code()).isEqualTo(ErrorCode.MODEL_TIMEOUT);
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    @DisplayName("其余未知故障 → 4001，文案保持 '模型调用失败，请稍后重试'（E2E 脚本靠它区分环境与回归）")
    void unknownFailuresKeepTheHistoricalMessage() {
        FailureClassifier.Failure failure =
                FailureClassifier.classify(new IllegalStateException("upstream boom"));

        assertThat(failure.code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
        assertThat(failure.message()).isEqualTo(FailureClassifier.MODEL_FAILED_MESSAGE);
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    @DisplayName("最具体的那条优先：被业务异常包住的轮数超限仍报 5002，不报外层那句话")
    void theMostSpecificCauseWins() {
        // 一旦外层是笼统的"模型调用失败"，用户又会被引到错误方向 —— 顺序本身就是契约
        RuntimeException wrapped = new BusinessException(ErrorCode.MODEL_UNAVAILABLE, "模型调用失败",
                new ToolCallLimitExceededException("knowledge_search", 40, null));

        assertThat(FailureClassifier.classify(wrapped).code())
                .isEqualTo(ErrorCode.AGENT_MAX_ITERATIONS_EXCEEDED);
    }

    @Test
    @DisplayName("转业务异常时保留原始异常作为 cause（排查要能一路看到底）")
    void toBusinessExceptionKeepsTheCause() {
        RuntimeException root = new IllegalStateException("boom");

        BusinessException converted = FailureClassifier.toBusinessException(root);

        assertThat(converted.getCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE.getCode());
        assertThat(converted.getMessage()).isEqualTo(FailureClassifier.MODEL_FAILED_MESSAGE);
        assertThat(converted.getCause()).isSameAs(root);
    }
}
