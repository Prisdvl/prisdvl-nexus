package com.lky.nexus.app.chat.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息视图。
 *
 * <p>Token / 延迟字段在 Phase 1 就落库并返回，Phase 10 的观测看板可以直接复用，
 * 不必再改表结构或接口。
 */
public record ChatMessageResponse(
        Long id,
        String role,
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer latencyMs,
        Integer firstTokenMs,
        String status,
        String errorMsg,
        LocalDateTime createdAt,
        // Agent 模式下的工具调用痕迹（普通对话为空列表）：刷新后仍能看到"检索了什么"
        List<MessageToolCall> toolCalls) {
}
