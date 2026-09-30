package com.lky.nexus.app.observe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * 观测计数注册表（Phase 10）。
 *
 * <p>为什么需要它：本项目从 B.27 到 B.29 反复验证过一条教训——
 * <b>被 catch 吞掉的异常/降级，如果不计数，就等于问题不存在</b>
 * （"表不存在被容错吞掉三周"、"LIKE 兜底空结果没人看见"都是实例）。
 * 这里把那些"静默路径"变成显式计数，巡检端点一翻就知道。
 *
 * <p>实现刻意简单：{@link LongAdder} 无锁自增 + {@link ConcurrentHashMap}，
 * 高并发下单调递增、永不丢失；快照是一次复制，调用方拿到的映射不会再变。
 */
@Component
public class ObservabilityRegistry {

    /** 已知的关键计数键（集中定义，避免字符串飘散在业务代码里）。 */
    public static final String AGENT_SEARCH_DEGRADED = "agent.search.degraded";
    public static final String AGENT_SEARCH_UNAVAILABLE = "agent.search.unavailable";
    public static final String RAG_RECORD_FAILED = "rag.record.failed";

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(String key) {
        counters.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    /** 快照：返回不可变副本（调用方修改不影响注册表）。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>();
        counters.forEach((key, adder) -> copy.put(key, adder.sum()));
        return Map.copyOf(copy);
    }
}