package com.lky.nexus.app.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentContext 纯单元测试：线程绑定、调用痕迹、隔离性。
 *
 * <p>这类上下文最容易出的 bug 是"串上下文"（A 用户调了工具，痕迹却跑到 B 用户头上），
 * 所以专门测两条线程互不干扰。
 */
class AgentContextTest {

    @Test
    @DisplayName("begin/end 把用户绑定到当前线程并能恢复")
    void bindsAndRestoresUser() {
        Long previous = AgentContext.begin(42L);
        try {
            assertThat(AgentContext.requireUserId()).isEqualTo(42L);
        } finally {
            AgentContext.end(previous);
        }
        assertThatThrownBy(AgentContext::requireUserId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("调用痕迹按轮次隔离，不跨 begin/end 泄漏")
    void traceIsPerRound() {
        AgentContext.begin(1L);
        try {
            AgentContext.record(new AgentContext.ToolCallRecord("knowledge_search", "查询", "5 条结果"));
            assertThat(AgentContext.trace()).hasSize(1);
        } finally {
            AgentContext.end(null);
        }
        // 结束后的痕迹应为空，而不是残留上一轮的
        assertThat(AgentContext.trace()).isEmpty();
    }

    @Test
    @DisplayName("两条线程的痕迹互不干扰（工具执行在线程池里，最容易串的就是这个）")
    void tracesAreThreadIsolated() throws Exception {
        Thread threadA = new Thread(() -> {
            AgentContext.begin(1L);
            try {
                AgentContext.record(new AgentContext.ToolCallRecord("tool_a", "输入A", "1 条"));
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                }
            } finally {
                AgentContext.end(null);
            }
            assertThat(AgentContext.trace()).isEmpty();
        });
        threadA.start();

        AgentContext.begin(2L);
        try {
            AgentContext.record(new AgentContext.ToolCallRecord("tool_b", "输入B", "2 条"));
            threadA.join();
            assertThat(AgentContext.trace())
                    .as("线程 B 不应看到线程 A 的痕迹")
                    .extracting(AgentContext.ToolCallRecord::tool)
                    .containsExactly("tool_b");
        } finally {
            AgentContext.end(null);
        }
    }
}