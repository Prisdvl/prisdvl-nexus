package com.lky.nexus.app.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 停止信号登记处的单元测试（不启动 Spring）。
 *
 * <p>重点验证鉴权语义：<b>只有发起者能停止自己的流</b>。
 * 否则任何登录用户猜到/拿到别人的 requestId 就能打断别人的生成——
 * 这是一个很容易被忽略的越权点（"停止"看起来无害，实际上是拒绝服务）。
 */
class StreamCancellationRegistryTest {

    private final StreamCancellationRegistry registry = new InMemoryStreamCancellationRegistry();

    @Test
    @DisplayName("登记后未被取消；发起者取消生效")
    void ownerCanCancel() {
        String requestId = "req-1";
        registry.register(requestId, 1001L);

        assertThat(registry.isCancelled(requestId)).isFalse();
        assertThat(registry.cancel(requestId, 1001L)).isTrue();
        assertThat(registry.isCancelled(requestId)).isTrue();
    }

    @Test
    @DisplayName("非发起者取消无效（防越权打断别人的生成）")
    void otherUserCannotCancel() {
        String requestId = "req-2";
        registry.register(requestId, 1001L);

        assertThat(registry.cancel(requestId, 9999L)).isFalse();
        assertThat(registry.isCancelled(requestId)).isFalse();
    }

    @Test
    @DisplayName("取消不存在的流返回 false（前端抢跑是正常情况，不应视为错误）")
    void cancellingUnknownStreamIsNoOp() {
        assertThat(registry.cancel("not-exist", 1001L)).isFalse();
        assertThat(registry.isCancelled("not-exist")).isFalse();
    }

    @Test
    @DisplayName("清理后状态归零（避免流结束后仍占用内存）")
    void clearRemovesEntry() {
        String requestId = "req-3";
        registry.register(requestId, 1001L);
        registry.cancel(requestId, 1001L);

        registry.clear(requestId);

        assertThat(registry.isCancelled(requestId)).isFalse();
        assertThat(registry.cancel(requestId, 1001L)).isFalse();
    }
}
