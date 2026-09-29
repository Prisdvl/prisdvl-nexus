package com.lky.nexus.app.chat.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.util.Locale;

/**
 * 会话模式：决定一次对话走哪条链路。
 *
 * <p>这是 Phase 1 → Phase 6 的主干分叉点：
 * <ul>
 *   <li>{@link #PLAIN} —— 纯对话，不检索、不用工具（Phase 1）；</li>
 *   <li>{@link #RAG} —— 先检索知识库再回答，必须带引用（Phase 3）；</li>
 *   <li>{@link #AGENT} —— 交给 Agent 自主决定检索与工具调用（Phase 6）。</li>
 * </ul>
 * 会话级保存该字段，因此后续轮次不必重复指定。
 */
public enum ChatMode {

    PLAIN,
    RAG,
    AGENT;

    /** 默认模式：第一版默认纯对话，避免用户在还没导入知识库时收到"检索为空"的困惑。 */
    public static final ChatMode DEFAULT = PLAIN;

    /**
     * 宽松解析：null/空 → {@link #DEFAULT}；大小写不敏感；非法值抛 1001（属于客户端参数错误）。
     */
    public static ChatMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的会话模式: %s（可选值：PLAIN / RAG / AGENT）".formatted(raw));
        }
    }
}
