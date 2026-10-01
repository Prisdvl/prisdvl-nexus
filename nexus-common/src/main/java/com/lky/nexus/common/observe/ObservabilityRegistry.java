package com.lky.nexus.common.observe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 观测计数注册表（Phase 10）。
 *
 * <p>为什么需要它：本项目从 B.27 到 B.29 反复验证过一条教训——
 * <b>被 catch 吞掉的异常/降级，如果不计数，就等于问题不存在</b>。
 * 这里把那些"静默路径"变成显式计数，巡检端点一翻就知道。
 *
 * <p>实现刻意简单：{@link LongAdder} 无锁自增 + {@link ConcurrentHashMap}，
 * 高并发下单调递增、永不丢失；快照是一次复制，调用方拿到的映射不会再变。
 *
 * <p>放在 <b>nexus-common</b>（纯 Java，无 Spring 依赖）：观测点散落在 knowledge 与 app 两层，
 * 依赖方向要求它位于公共底座；bean 装配由 app 层的 {@code AppConfig} 负责。
 */
public class ObservabilityRegistry {

    /** 已知的关键计数键（集中定义，避免字符串飘散在业务代码里）。 */
    public static final String AGENT_SEARCH_DEGRADED = "agent.search.degraded";
    public static final String AGENT_SEARCH_UNAVAILABLE = "agent.search.unavailable";
    /**
     * 检索跑完了但没有一条可引用的资料（4.4）：这是"模型可能改用自身知识回答"的触发条件。
     * 与 {@code AGENT_SEARCH_UNAVAILABLE}（基础设施不可用）不同——这里是工具装了、也真的跑了，
     * 只是没东西可引用；也不是 {@code AGENT_TOOLS_EMPTY}（一个工具都没装配）。
     */
    public static final String AGENT_SEARCH_EMPTY = "agent.search.empty";
    public static final String RAG_RECORD_FAILED = "rag.record.failed";
    /** 工具调用成功/失败（4.3 执行壳）：失败不是异常，是"回灌给模型的观察结果"，因此必须计数。 */
    public static final String TOOL_CALL_SUCCEEDED = "tool.call.succeeded";
    public static final String TOOL_CALL_FAILED = "tool.call.failed";
    /** 装配到 0 个工具的 Agent：静默降级的经典形态（对话照常，只是再也不检索了）。 */
    public static final String AGENT_TOOLS_EMPTY = "agent.tools.empty";
    /**
     * 系统提示里点名了某个工具，但这次装配没有给它：提示词在命令模型调用一个它看不见的工具。
     * 4.3 才有的一种不一致（在此之前提示词里的那个工具永远会被装上），必须留痕。
     */
    public static final String TOOL_PROMPT_MISMATCH = "tool.prompt.mismatch";

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