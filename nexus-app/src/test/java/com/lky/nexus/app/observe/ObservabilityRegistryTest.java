package com.lky.nexus.app.observe;

import com.lky.nexus.common.observe.ObservabilityRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测注册表纯单元测试：计数累加、快照隔离。
 */
class ObservabilityRegistryTest {

    @Test
    @DisplayName("自增后快照能看到计数")
    void incrementsAndSnapshots() {
        ObservabilityRegistry registry = new ObservabilityRegistry();
        registry.increment(ObservabilityRegistry.AGENT_SEARCH_DEGRADED);
        registry.increment(ObservabilityRegistry.AGENT_SEARCH_DEGRADED);
        registry.increment(ObservabilityRegistry.RAG_RECORD_FAILED);

        assertThat(registry.snapshot())
                .containsEntry(ObservabilityRegistry.AGENT_SEARCH_DEGRADED, 2L)
                .containsEntry(ObservabilityRegistry.RAG_RECORD_FAILED, 1L);
    }

    @Test
    @DisplayName("快照是副本：拿走之后继续自增不影响已拿到的数据")
    void snapshotIsIsolated() {
        ObservabilityRegistry registry = new ObservabilityRegistry();
        registry.increment("a");

        var first = registry.snapshot();
        registry.increment("a");
        registry.increment("a");

        assertThat(first.get("a")).isEqualTo(1L);
        assertThat(registry.snapshot().get("a")).isEqualTo(3L);
    }

    @Test
    @DisplayName("并发自增不丢失（LongAdder 的保障，这里验证行为）")
    void concurrentIncrementsAreNotLost() throws Exception {
        ObservabilityRegistry registry = new ObservabilityRegistry();
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    registry.increment("concurrent");
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(registry.snapshot().get("concurrent")).isEqualTo(8000L);
    }
}