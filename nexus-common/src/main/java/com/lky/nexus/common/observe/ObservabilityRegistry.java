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

    /**
     * 安全模块的存储（Redis / 内存）不可用（5.6）。限流器 fail-open 意味着"放行"，
     * 若不计数，Redis 挂掉就等于**悄悄不设防**——这条计数就是那句"被吞掉的降级必须留痕"。
     */
    public static final String SECURITY_STORE_UNAVAILABLE = "security.store.unavailable";
    /**
     * 令牌版本缓存失效失败（5.6）：改密码已经把 DB 里的版本推上去，但缓存没删掉，
     * 于是旧令牌会在缓存 TTL 窗口内继续有效。这是**有界但真实**的泄漏，必须可见。
     */
    public static final String SECURITY_TOKEN_VERSION_INVALIDATE_FAILED = "security.token-version.invalidate.failed";
    /** token 用量写回失败（5.6）：模型已经调用了，账没记上，只能计数、不能回滚。 */
    public static final String QUOTA_RECORD_FAILED = "quota.record.failed";
    /**
     * 一次模型调用没有归属到任何用户（5.6）：调用方忘了把 userId 放进 Advisor 上下文。
     * 计入 token 配额的功能靠"每个入口都传 userId"，漏一个就会**静默少计**——
     * 把它变成一条可见的计数，免得这个缺口只在上线对账时才发现。
     */
    public static final String QUOTA_USAGE_UNATTRIBUTED = "quota.usage.unattributed";

    /**
     * 多库检索里有知识库失败（5.5.1）：这一条是"失败隔离"与"静默少搜"之间的分界线。
     *
     * <p>隔离之前，某个库挂了会让整个问答报错——响亮，但至少没人被误导。
     * 隔离之后那一个库会安静地什么都不贡献，于是"这个库挂了"与"这个库里本来就没内容"
     * 在用户侧长得一模一样（模型会说"你的笔记里没有 X"，而 X 就在那个库里）。
     * 计数是这条分界线的一部分——另两部分是 WARN 日志与检索事件里的如实告知。
     *
     * <p><b>全部库失败时不计这一条</b>：那时会直接抛异常，本身就是响亮的失败。
     */
    public static final String RETRIEVAL_KB_FAILED = "retrieval.kb.failed";

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