package com.lky.nexus.common.observe;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 观测注册表：静默降级计数 + Micrometer 上报（Phase 5.9 六）。
 *
 * <p>为什么要单独钉"转发给 Micrometer"这一层：{@code /actuator/metrics} 上
 * 一个指标都没有<b>不会报任何错</b>——页面照常打开、只是空的，看起来像"这个服务确实没流量"。
 * 那种失效只能靠测试发现。同理，转发本身如果抛异常，会把一条本来正常的业务路径带崩
 * （观测是附加价值，绝不能反噬主流程），所以"没有 Micrometer 时安然无恙"也必须钉住。
 */
class ObservabilityRegistryTest {

    @Test
    @DisplayName("increment 同时进快照与 Micrometer（指标名带 nexus. 前缀）")
    void incrementPublishesToMicrometerUnderPrefixedName() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservabilityRegistry registry = new ObservabilityRegistry(meters);

        registry.increment(ObservabilityRegistry.RETRIEVAL_KB_FAILED);
        registry.increment(ObservabilityRegistry.RETRIEVAL_KB_FAILED);
        registry.increment(ObservabilityRegistry.CHAT_REQUESTS);

        assertThat(registry.snapshot())
                .containsEntry(ObservabilityRegistry.RETRIEVAL_KB_FAILED, 2L)
                .containsEntry(ObservabilityRegistry.CHAT_REQUESTS, 1L);
        assertThat(meters.get("nexus.retrieval.kb.failed").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("nexus.chat.requests").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("nexus.retrieval.kb.failed").meter().getId().getType())
                .isEqualTo(Meter.Type.COUNTER);
    }

    @Test
    @DisplayName("recordLlmCall 一次记全三样：调用次数（counter）、token 量（summary）、耗时（timer）")
    void recordLlmCallPublishesCallsTokensAndLatency() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservabilityRegistry registry = new ObservabilityRegistry(meters);

        registry.recordLlmCall(120, 30, 950);
        registry.recordLlmCall(null, null, 50);

        assertThat(registry.snapshot()).containsEntry(ObservabilityRegistry.LLM_CALLS, 2L);
        assertThat(meters.get("nexus.llm.calls").counter().count()).isEqualTo(2.0);

        // token 总量 = 150 + 0：供应商没回传 usage 的那次按 0 计，但**调用次数照样算**——
        // 把"没回传"当成"没调用"会让指标在某些供应商上整片为空
        assertThat(meters.get("nexus.llm.tokens").summary().totalAmount()).isEqualTo(150.0);

        assertThat(meters.get("nexus.llm.latency").timer().count()).isEqualTo(2);
        assertThat(meters.get("nexus.llm.latency").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(1000.0);
    }

    @Test
    @DisplayName("负值（负 token / 时钟回拨的耗时）被丢弃，不会污染累计量")
    void negativeValuesAreDropped() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservabilityRegistry registry = new ObservabilityRegistry(meters);

        registry.recordAmount(ObservabilityRegistry.LLM_TOKENS, -5);
        registry.recordDuration(ObservabilityRegistry.LLM_LATENCY, -1);

        assertThat(meters.find("nexus.llm.tokens").summary()).isNull();
        assertThat(meters.find("nexus.llm.latency").timer()).isNull();
    }

    @Test
    @DisplayName("没有 Micrometer 时一切照常：快照仍然计数，指标记录是空操作而不是异常")
    void worksWithoutMicrometer() {
        ObservabilityRegistry registry = new ObservabilityRegistry();

        assertThatCode(() -> {
            registry.increment(ObservabilityRegistry.ERRORS);
            registry.recordAmount(ObservabilityRegistry.LLM_TOKENS, 100);
            registry.recordDuration(ObservabilityRegistry.LLM_LATENCY, 10);
            registry.recordLlmCall(1, 2, 3);
        }).doesNotThrowAnyException();

        assertThat(registry.snapshot())
                .containsEntry(ObservabilityRegistry.ERRORS, 1L)
                .containsEntry(ObservabilityRegistry.LLM_CALLS, 1L)
                // 累计量与耗时只进 Micrometer：它们不是"降级发生了多少次"，
                // 混进这张表会让它失去焦点
                .doesNotContainKeys(ObservabilityRegistry.LLM_TOKENS, ObservabilityRegistry.LLM_LATENCY);
    }

    @Test
    @DisplayName("meterName 与实际注册的名字一致（排查时照着这个名字去 /actuator/metrics 找）")
    void meterNameMatchesWhatIsRegistered() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservabilityRegistry registry = new ObservabilityRegistry(meters);

        registry.increment(ObservabilityRegistry.RETRIEVAL_REQUESTS);

        assertThat(ObservabilityRegistry.meterName(ObservabilityRegistry.RETRIEVAL_REQUESTS))
                .isEqualTo("nexus.retrieval.requests");
        assertThat(meters.find(ObservabilityRegistry.meterName(ObservabilityRegistry.RETRIEVAL_REQUESTS)).counter())
                .isNotNull();
    }
}
