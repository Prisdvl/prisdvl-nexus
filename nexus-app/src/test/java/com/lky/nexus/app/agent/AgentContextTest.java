package com.lky.nexus.app.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentContext 纯单元测试：用户身份的可取性、调用痕迹、上下文隔离。
 *
 * <p>其中 {@link #traceCrossesThreads()} 是针对一次真实线上缺陷的回归测试：
 * 早先上下文放在 ThreadLocal 里，非流式调用能跑通，流式对话里工具却被框架在
 * 另一个线程上执行，读不到用户 → 抛异常 → 框架把它当工具结果回填 → 模型改用自身知识作答，
 * 全程无报错。改成 ToolContext 后上下文随请求走，这条测试守着"跨线程仍能拿到上下文"。
 */
class AgentContextTest {

    @Test
    @DisplayName("of 出来的上下文能取回用户 id")
    void carriesUserId() {
        ToolContext context = AgentContext.toolContext(42L);

        assertThat(AgentContext.requireUserId(context)).isEqualTo(42L);
    }

    @Test
    @DisplayName("没有用户信息时取用户必须抛异常，而不是返回 null 去检索所有人的库")
    void missingUserIsRejected() {
        assertThatThrownBy(() -> AgentContext.requireUserId(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少用户上下文");

        assertThatThrownBy(() -> AgentContext.requireUserId(new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("两个上下文互不可见（每次请求各有各的用户，不串号）")
    void contextsAreIsolated() {
        ToolContext alice = AgentContext.toolContext(1L);
        ToolContext bob = AgentContext.toolContext(2L);

        assertThat(AgentContext.requireUserId(alice)).isEqualTo(1L);
        assertThat(AgentContext.requireUserId(bob)).isEqualTo(2L);
    }

    @Test
    @DisplayName("痕迹跨线程可达：工具在别的线程上执行，监听器照样收得到")
    void traceCrossesThreads() throws Exception {
        List<AgentContext.ToolCallRecord> sink =
                Collections.synchronizedList(new ArrayList<>());
        ToolContext context = contextWith(7L, AgentContext.collector(sink));
        CountDownLatch done = new CountDownLatch(1);

        Thread toolThread = new Thread(() -> {
            try {
                AgentContext.record(context,
                        new AgentContext.ToolCallRecord("knowledge_search", "缓存击穿", "5 条结果"));
            } finally {
                done.countDown();
            }
        });
        toolThread.start();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        toolThread.join();
        assertThat(sink)
                .as("工具线程写下的痕迹，发起调用的这一侧必须看得到")
                .extracting(AgentContext.ToolCallRecord::resultSummary)
                .containsExactly("5 条结果");
    }

    @Test
    @DisplayName("挂了监听器：call 先到、result 后到（流式对话据此实时推事件）")
    void listenerReceivesCallThenResult() {
        List<String> events = new ArrayList<>();
        ToolContext context = contextWith(7L, new AgentContext.ToolCallListener() {
            @Override
            public void onCall(String tool, String input) {
                events.add("call:" + tool + ":" + input);
            }

            @Override
            public void onResult(AgentContext.ToolCallRecord record) {
                events.add("result:" + record.resultSummary());
            }
        });

        // 真实执行顺序：工具先报"开始检索"，检索完再报结果
        AgentContext.call(context, "knowledge_search", "缓存击穿");
        AgentContext.record(context, new AgentContext.ToolCallRecord("knowledge_search", "缓存击穿", "5 条结果"));

        assertThat(events).containsExactly("call:knowledge_search:缓存击穿", "result:5 条结果");
    }

    @Test
    @DisplayName("只收痕迹的监听器：onCall 忽略、onResult 入账")
    void collectorOnlyCollectsResults() {
        List<AgentContext.ToolCallRecord> sink = new ArrayList<>();
        ToolContext context = contextWith(3L, AgentContext.collector(sink));

        AgentContext.call(context, "knowledge_search", "任意查询");
        assertThat(sink).as("开始事件不产生痕迹").isEmpty();

        AgentContext.record(context, new AgentContext.ToolCallRecord("knowledge_search", "任意查询", "0 条结果"));
        assertThat(sink).hasSize(1);
    }

    @Test
    @DisplayName("没挂监听器时 call/record 是空操作（不抛异常）")
    void withoutListenerIsNoop() {
        ToolContext context = AgentContext.toolContext(3L);

        AgentContext.call(context, "knowledge_search", "任意查询");
        AgentContext.record(context, new AgentContext.ToolCallRecord("knowledge_search", "任意查询", "0 条结果"));
    }

    /** 框架会把 toolContext(map) 包成 ToolContext 注入工具方法，测试里照做一遍。 */
    private static ToolContext contextWith(Long userId, AgentContext.ToolCallListener listener) {
        return new ToolContext(AgentContext.of(userId, listener));
    }
}
