package com.lky.nexus.common.observe;

import java.util.Set;

/**
 * 观测用的场景名（写进 {@code observability_call.scene}）。
 *
 * <p>用常量而不是散落的字符串字面量：这些值会进报表的分组维度，
 * 少写一个字母不会编译失败，只会在 "models/trend 按 scene 过滤" 时静默返回空。
 *
 * <p>命名规则：<b>端点 + 是否流式</b>。同一个端点的同步版与流式版延迟量级差一个量级，
 * 合并成一个场景会让平均延迟变成一个既不像 chat 也不像 stream 的数字。
 */
public final class ObserveScenes {

    /** 同步对话（{@code POST /api/chat/...} 非流式）。 */
    public static final String CHAT = "chat";

    /** 流式对话（SSE）。 */
    public static final String CHAT_STREAM = "chat.stream";

    /** 知识库问答（同步）。 */
    public static final String RAG = "rag";

    /** 知识库问答（流式）。 */
    public static final String RAG_STREAM = "rag.stream";

    /** Agent 运行（含自动化任务触发的运行，它们走的就是这条链路）。 */
    public static final String AGENT = "agent";

    /** Agent 流式运行。 */
    public static final String AGENT_STREAM = "agent.stream";

    /** 系统调用：检索重排。没有 userId，不进任何用户的报表。 */
    public static final String RERANK = "rerank";

    /** 需要计入 {@code overview.ragCalls} 的场景。 */
    public static final Set<String> RAG_SCENES = Set.of(RAG, RAG_STREAM);

    private ObserveScenes() {
    }
}
