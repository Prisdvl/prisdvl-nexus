package com.lky.nexus.app.agent;

import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用的上下文：<b>用户身份</b> + <b>过程监听器</b>，随请求通过 Spring AI 的
 * {@link ToolContext} 传给工具方法。
 *
 * <p>为什么需要它：LLM 的工具调用发生在框架的响应处理链路里，那里没有 HTTP 请求上下文，
 * 拿不到"当前用户"，而 {@code knowledge_search} 必须知道"搜谁的库"。
 *
 * <p><b>为什么是 ToolContext 而不是 ThreadLocal</b>——这是一次真实踩坑的记录：
 * 最初用 ThreadLocal 绑定当前用户，一次性调用（{@code AgentService#ask}）跑得通，
 * 流式对话却静默失效。根因是流式链路上工具的执行线程<b>不是</b>绑定上下文的那个线程
 * （订阅发生在 boundedElastic，而工具由响应式 HTTP 客户端在聚合完 tool_calls 之后执行）。
 * 工具读不到用户 id，抛"缺少用户上下文"，框架把这句异常当作<b>工具结果</b>回填给模型，
 * 模型于是改用自身知识作答：表面症状就是"Agent 对话从来不检索知识库"，而且一条报错都没有。
 * ToolContext 由框架携带并注入到工具方法参数上，与线程无关，这一类 bug 从根上不存在。
 *
 * <p>上下文里的内容只在我们自己的进程内流转，<b>不会发给模型</b>：框架生成工具入参 schema 时
 * 会跳过 {@code ToolContext} 参数（已核对 Spring AI 2.0.1 的 {@code JsonSchemaGenerator}）。
 *
 * <p>Phase 4.1 新增 {@link #KB_IDS}：Agent 可以绑定知识库，工具据此把检索范围从
 * "该用户全部库"收窄到"这个 Agent 绑定的库"。它与用户 id 一样属于<b>上下文</b>而不是工具入参——
 * 让模型自己填知识库 id 既没用（它不知道 id）也不安全（可以填别人的）。
 */
public final class AgentContext {

    /** 用户 id 在 ToolContext 里的键。 */
    public static final String USER_ID = "nexus.userId";

    /** 知识库范围在 ToolContext 里的键；<b>不存在或为空 = 不限制</b>（搜索该用户全部知识库）。 */
    public static final String KB_IDS = "nexus.kbIds";

    /** 过程监听器在 ToolContext 里的键（流式对话用它把"正在检索…"实时推给前端）。 */
    public static final String LISTENER = "nexus.toolCallListener";

    private AgentContext() {
    }

    /** 只带用户的上下文：一次性调用用它，调用方自行决定要不要收痕迹。 */
    public static Map<String, Object> of(Long userId) {
        return of(userId, null);
    }

    /**
     * 带用户、并挂一个过程监听器的上下文。
     *
     * <p>返回 {@code Map} 是因为这是框架要的形状：{@code ChatClient.prompt().toolContext(map)}
     * 收的就是它，框架再把它包成 {@link ToolContext} 注入工具方法。
     */
    public static Map<String, Object> of(Long userId, ToolCallListener listener) {
        return of(userId, null, listener);
    }

    /** 带用户、知识库范围与过程监听器的上下文（空 kbIds = 不限制）。 */
    public static Map<String, Object> of(Long userId, List<Long> kbIds, ToolCallListener listener) {
        Map<String, Object> context = new HashMap<>();
        context.put(USER_ID, userId);
        if (kbIds != null && !kbIds.isEmpty()) {
            context.put(KB_IDS, List.copyOf(kbIds));
        }
        if (listener != null) {
            context.put(LISTENER, listener);
        }
        return context;
    }

    /** 我们自己直接调工具（不经模型，例如 MCP 的 tools/call）时用的上下文。 */
    public static ToolContext toolContext(Long userId) {
        return new ToolContext(of(userId));
    }

    /**
     * 取当前工具调用所属的用户。
     *
     * <p>拿不到就抛异常而不是返回 null：检索"所有人的库"比检索失败危险得多。
     */
    public static Long requireUserId(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(USER_ID);
        if (value == null) {
            throw new IllegalStateException(
                    "工具调用缺少用户上下文（ToolContext 里没有 %s）".formatted(USER_ID));
        }
        return (Long) value;
    }

    /**
     * 取当前工具调用的知识库范围。
     *
     * <p>返回 {@code null} 表示<b>不限制</b>（搜索该用户的全部知识库）——这是"没绑定"与
     * "绑定了零个库"共用的语义，也是 Phase 4.1 之前唯一的行为，所以老链路不需要改。
     * 若返回空列表就意味着"绑了 0 个库，谁都搜不了"，那会和"不限制"撞在一起，
     * 因此这里把空集合统一收敛成 null。
     */
    @SuppressWarnings("unchecked")
    public static List<Long> kbIds(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(KB_IDS);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return (List<Long>) list;
    }

    /**
     * 工具<b>即将执行</b>（流式场景用来推"正在检索…"）。
     *
     * <p>与 {@link #record} 的区别是时机：这里还没有结果，前端据此展示进行中的状态。
     */
    public static void call(ToolContext toolContext, String tool, String input) {
        ToolCallListener listener = listener(toolContext);
        if (listener != null) {
            listener.onCall(tool, input);
        }
    }

    /** 工具执行结束，把痕迹交给监听器（没有监听器时是空操作）。 */
    public static void record(ToolContext toolContext, ToolCallRecord record) {
        ToolCallListener listener = listener(toolContext);
        if (listener != null) {
            listener.onResult(record);
        }
    }

    /**
     * 工具检索到、并<b>实际交给模型</b>的资料片段，顺序即回答里的 {@code [1..n]} 编号。
     *
     * <p>与 {@link #record} 分开是因为它们回答的是两个不同的问题：
     * 痕迹回答"这一步做了什么、命中几条"（给人看的过程），
     * 这里回答"这一轮回答是照着哪几段资料说的"（可追溯到片段本身）。
     * 只有后者能持久化成一列引用——痕迹里的"3 条结果"在刷新之后
     * 无法还原出是哪三段、出自哪篇文档的哪一节。
     *
     * <p><b>必须由调用线程在扇出结束后调用</b>：它最终可能写 SSE 监听器（不是线程安全的），
     * 规则与 {@link #record} 完全一致。
     */
    public static void retrieval(ToolContext toolContext, String tool,
                                 List<ChunkSearchService.SearchHit> hits) {
        ToolCallListener listener = listener(toolContext);
        if (listener != null) {
            listener.onRetrieval(tool, hits);
        }
    }

    /** 只收痕迹、不推事件的监听器（一次性调用用：把痕迹收进调用方自己的列表）。 */
    public static ToolCallListener collector(List<ToolCallRecord> sink) {
        return new ToolCallListener() {
            @Override
            public void onCall(String tool, String input) {
                // 一次性调用没有"进行中"的界面状态，开始事件无处可用
            }

            @Override
            public void onResult(ToolCallRecord record) {
                sink.add(record);
            }
        };
    }

    private static ToolCallListener listener(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(LISTENER);
        return value instanceof ToolCallListener listener ? listener : null;
    }

    /** 一次工具调用的痕迹（"用户可控"的透明性来源：每一步做了什么都要让用户看得见）。 */
    public record ToolCallRecord(String tool, String input, String resultSummary) {
    }

    /** 工具过程事件：流式对话把它转成 SSE 事件；一次性调用只需收痕迹。 */
    public interface ToolCallListener {

        /** 工具开始执行（还没有结果）。 */
        void onCall(String tool, String input);

        /** 工具执行结束，带结果摘要。 */
        void onResult(ToolCallRecord record);

        /**
         * 工具检索并交给模型的资料片段（顺序即 {@code [1..n]}）。
         *
         * <p>默认空实现：只关心过程痕迹的调用方（一次性调用的收集器、测试桩）
         * 不该被强制实现一个它用不上的方法——强制实现的后果通常是写一个空方法，
         * 那和默认空实现在行为上完全一样，只是多了一处噪声。
         */
        default void onRetrieval(String tool, List<ChunkSearchService.SearchHit> hits) {
        }
    }
}
