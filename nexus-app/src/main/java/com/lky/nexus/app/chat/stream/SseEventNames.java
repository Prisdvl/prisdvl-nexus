package com.lky.nexus.app.chat.stream;

/**
 * SSE 事件名常量（协议见 docs/api.md 5.2）。
 *
 * <p>事件名是前后端契约的一部分：改名字等于破坏前端。集中在这里，
 * 避免"前端监听 delta、后端发 chunk"这类只有联调才发现的低级问题。
 *
 * <p>Phase 1 实际发送：{@link #META}、{@link #DELTA}、{@link #USAGE}、{@link #DONE}、{@link #ERROR}。
 * 其余为 Phase 3/5/6 预留（检索、引用、工具调用、人工确认），现在就把名字定下来，
 * 前端可以提前把分支写好。
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
    public static final String RETRIEVAL = "retrieval";
    public static final String CITATION = "citation";

    // ---------- Phase 5 / 6（工具与 Agent）----------
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String APPROVAL_REQUIRED = "approval_required";

    private SseEventNames() {
    }
}
