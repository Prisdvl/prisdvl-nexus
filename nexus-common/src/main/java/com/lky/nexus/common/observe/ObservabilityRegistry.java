package com.lky.nexus.common.observe;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 观测注册表（Phase 10 起为"静默路径计数"，5.9 起兼作 Micrometer 指标的<b>唯一</b>上报口）。
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
 *
 * <h2>与 Micrometer 的关系（5.9）</h2>
 * <b>只有一个上报口，两个读法</b>：本类既是 {@code /api/observability/summary} 的数据源，
 * 又把同一批点转发给 {@link MeterRegistry}（于是 {@code /actuator/metrics} 也能看到）。
 * 刻意<b>不</b>新写一套 metrics 采集——那会让同一个事实有两处记录，
 * 而两处一旦对不上，没人知道该信哪个。
 *
 * <p>转发是<b>可选</b>的：{@link #ObservabilityRegistry()} 不接 Micrometer，
 * 所有指标记录退化为空操作（纯单测、无 Spring 的环境照常跑）。同理，
 * {@code recordAmount} / {@code recordDuration} 只进 Micrometer、不进
 * {@link #snapshot()}——那个快照回答的是"哪些降级发生了多少次"，
 * token 总量与耗时不是降级，混进去会让这张表失去焦点。
 */
public class ObservabilityRegistry {

    /** Micrometer 指标名的统一前缀（{@code agent.search.degraded} → {@code nexus.agent.search.degraded}）。 */
    private static final String METER_PREFIX = "nexus.";

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

    // ------------------------------------------------------------------
    // 运行指标（5.9 六）：经 Micrometer 暴露在 /actuator/metrics。
    // 只列"需要新记的"，降级类计数沿用上面那批常量——它们的键本来就是指标名的一部分
    // ------------------------------------------------------------------

    /** 模型调用次数（含 Agent 工具循环的每一轮、以及重排这种系统调用）。 */
    public static final String LLM_CALLS = "llm.calls";
    /** 单次模型调用消耗的 token（prompt + completion），累计型。 */
    public static final String LLM_TOKENS = "llm.tokens";
    /** 单次模型调用的耗时（订阅到结束），计时型。 */
    public static final String LLM_LATENCY = "llm.latency";
    /** 对话请求数（流式与非流式各算一次）。 */
    public static final String CHAT_REQUESTS = "chat.requests";
    /** 一次对话的端到端耗时（从入口到落库/返回），计时型。 */
    public static final String CHAT_LATENCY = "chat.latency";
    /** 真正发起检索的次数（不含"没有库可搜"这类根本没走到检索的请求）。 */
    public static final String RETRIEVAL_REQUESTS = "retrieval.requests";
    /** Agent 执行次数（一次性调用与流式对话各算一次）。 */
    public static final String AGENT_EXECUTIONS = "agent.executions";
    /** 以错误结束的请求次数（全局异常处理器与流式失败各记一次）。 */
    public static final String ERRORS = "errors";

    private final MeterRegistry meters;

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    /**
     * 不带 Micrometer 的注册表：指标记录退化为空操作。
     *
     * <p>保留它是为了让 nexus-common / nexus-ai / nexus-knowledge 的单测
     * 不必为了跑起来而拖进一整个指标后端——那些测试要验的是业务行为，
     * 不是"指标有没有上报"。
     */
    public ObservabilityRegistry() {
        this(null);
    }

    public ObservabilityRegistry(MeterRegistry meters) {
        this.meters = meters;
    }

    public void increment(String key) {
        counters.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        if (meters != null) {
            meters.counter(METER_PREFIX + key).increment();
        }
    }

    /**
     * 累计型指标（例如 token 数）：只进 Micrometer，不进 {@link #snapshot()}。
     *
     * <p>负值直接丢弃：这些数的来源是供应商回传的 usage，出现负数只可能是解析/上游异常，
     * 记进去会让"今天用了多少 token"这个数永远说不清。
     */
    public void recordAmount(String key, long amount) {
        if (meters == null || amount < 0) {
            return;
        }
        meters.summary(METER_PREFIX + key).record(amount);
    }

    /** 计时型指标（毫秒）：只进 Micrometer，不进 {@link #snapshot()}。负值（时钟回拨）丢弃。 */
    public void recordDuration(String key, long millis) {
        if (meters == null || millis < 0) {
            return;
        }
        meters.timer(METER_PREFIX + key).record(Duration.ofMillis(millis));
    }

    /**
     * 一次模型调用：次数、token、耗时<b>一次记全</b>。
     *
     * <p>合成一个方法是因为这三件事来自同一处观测点（{@code UsageMeteringAdvisor}），
     * 分开写会让"这个入口到底记了几样"变得要翻三处才看得清；
     * 而漏记一样不会有任何报错，只会让某张图永远为空。
     *
     * @param promptTokens     输入 token（null 按 0）
     * @param completionTokens 输出 token（null 按 0）
     * @param latencyMillis    本次调用耗时
     */
    public void recordLlmCall(Integer promptTokens, Integer completionTokens, long latencyMillis) {
        increment(LLM_CALLS);
        recordAmount(LLM_TOKENS, (promptTokens == null ? 0 : promptTokens)
                + (completionTokens == null ? 0 : completionTokens));
        recordDuration(LLM_LATENCY, latencyMillis);
    }

    /** Micrometer 指标名（供排查时对照 {@code /actuator/metrics/{name}} 用）。 */
    public static String meterName(String key) {
        return METER_PREFIX + key;
    }

    /** 快照：返回不可变副本（调用方修改不影响注册表）。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>();
        counters.forEach((key, adder) -> copy.put(key, adder.sum()));
        return Map.copyOf(copy);
    }
}