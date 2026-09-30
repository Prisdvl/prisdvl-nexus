package com.lky.nexus.app.chat.stream;

/**
 * SSE 事件名常量（协议见 docs/api.md 5.2）。
 *
 * <p>事件名是前后端契约的一部分：改名字等于破坏前端。集中在这里，
 * 避免"前端监听 delta、后端发 chunk"这类只有联调才发现的低级问题。
 *
 * <p>当前实际发送：{@link #META}、{@link #DELTA}、{@link #USAGE}、{@link #DONE}、{@link #ERROR}
 * （Phase 1）、{@link #TOOL_CALL} / {@link #TOOL_RESULT}（Phase 5 Agent）、
 * {@link #RETRIEVAL}（Phase 3.4 RAG）。
 * {@link #CITATION} 与 {@link #APPROVAL_REQUIRED} 仍是预留给后续阶段的名称。
 */
public final class SseEventNames {

    /** 流建立：返回 requestId / conversationId / 两条消息 id / 使用的模型角色。 */
    public static final String META = "meta";

    /** 文本增量。 */
    public static final String DELTA = "delta";

    /** Token 用量与延迟（生成结束后发送）。 */
    public static final String USAGE = "usage";

    /** 正常结束。 */
    public static final String DONE = "done";

    /** 异常结束。 */
    public static final String ERROR = "error";

    // ---------- Phase 3（RAG）----------
    /** 检索完成、生成之前：命中片段与来源（前端据此显示"检索到 N 个片段"）。 */
    public static final String RETRIEVAL = "retrieval";

    /** 引用明细。当前引用随 {@code done} 事件返回（回答结束才能解析 [n]），故仍是预留。 */
    public static final String CITATION = "citation";

    // ---------- Phase 5 / 6（工具与 Agent）----------
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String APPROVAL_REQUIRED = "approval_required";

    private SseEventNames() {
    }
}
