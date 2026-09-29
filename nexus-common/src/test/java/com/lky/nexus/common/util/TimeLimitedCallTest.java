package com.lky.nexus.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时执行工具测试。
 *
 * <p>它防的是"外部依赖退化时无限期阻塞"——这种问题在功能测试里看不出来
 * （功能全都正确，只是慢到不可用），必须用时间维度的断言才测得到。
 */
class TimeLimitedCallTest {

    @Test
    @DisplayName("正常返回：结果照常拿到")
    void returnsResultWhenFastEnough() {
        assertThat(TimeLimitedCall.run(Duration.ofSeconds(2), () -> "ok")).contains("ok");
    }

    @Test
    @DisplayName("超时：返回空而不是一直等（外部依赖退化时的关键行为）")
    void givesUpOnTimeout() {
        long start = System.currentTimeMillis();

        var result = TimeLimitedCall.run(Duration.ofMillis(200), () -> {
            sleep(3000);
            return "too late";
        });

        assertThat(result).isEmpty();
        assertThat(System.currentTimeMillis() - start)
                .as("应在超时时间附近返回，而不是等满 3 秒")
                .isLessThan(2000);
    }

    @Test
    @DisplayName("抛异常：同样返回空（调用方只需处理「有 / 没有」两种情况）")
    void swallowsFailure() {
        assertThat(TimeLimitedCall.run(Duration.ofSeconds(1), () -> {
            throw new IllegalStateException("网关 502");
        })).isEmpty();
    }

    @Test
    @DisplayName("返回 null 视作没有结果；超时时间为零表示不设限")
    void handlesNullAndUnlimited() {
        assertThat(TimeLimitedCall.run(Duration.ofSeconds(1), () -> null)).isEmpty();
        assertThat(TimeLimitedCall.run(Duration.ZERO, () -> "sync")).contains("sync");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
