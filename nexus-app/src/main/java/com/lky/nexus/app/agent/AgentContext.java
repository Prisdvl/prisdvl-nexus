package com.lky.nexus.app.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Agent 工具执行的线程上下文。
 *
 * <p>为什么需要它：LLM 的工具调用发生在 ChatClient 的响应处理线程里，
 * 没有请求上下文（拿不到当前用户）。而 {@code knowledge_search} 必须知道"搜谁的库"。
 * 因此 AgentService 在调用前后显式设置/恢复当前用户——这是"线程绑定上下文"，
 * 不是全局状态，跨线程不泄漏。
 */
public final class AgentContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<ToolCallRecord>> TRACE = new ThreadLocal<>();

    private AgentContext() {
    }

    /** 开始一次工具循环：绑定用户并清空上一轮的调用痕迹。 */
    public static Long begin(Long userId) {
        Long previous = USER_ID.get();
        USER_ID.set(userId);
        List<ToolCallRecord> trace = new ArrayList<>();
        TRACE.set(trace);
        return previous;
    }

    public static void end(Long previous) {
        USER_ID.set(previous);
        TRACE.remove();
    }

    public static Long requireUserId() {
        return Optional.ofNullable(USER_ID.get())
                .orElseThrow(() -> new IllegalStateException("工具调用缺少用户上下文（AgentContext 未初始化）"));
    }

    public static void record(ToolCallRecord record) {
        List<ToolCallRecord> trace = TRACE.get();
        if (trace != null) {
            trace.add(record);
        }
    }

    public static List<ToolCallRecord> trace() {
        List<ToolCallRecord> trace = TRACE.get();
        return trace == null ? List.of() : List.copyOf(trace);
    }

    /** 一次工具调用的痕迹（"用户可控"的透明性来源：每一步做了什么都要让用户看得见）。 */
    public record ToolCallRecord(String tool, String input, String resultSummary) {
    }
}