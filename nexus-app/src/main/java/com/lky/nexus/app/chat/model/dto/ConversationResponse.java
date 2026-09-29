package com.lky.nexus.app.chat.model.dto;

import java.time.LocalDateTime;

/**
 * 会话视图（列表与详情共用）。
 *
 * <p>{@code id} 是 {@code Long} → 全局序列化为字符串（ADR-017）；
 * {@code messageCount} 用 {@code Integer} → 保持数字形态。
 */
public record ConversationResponse(
        Long id,
        String title,
        String mode,
        String modelRole,
        Integer messageCount,
        LocalDateTime lastMessageAt,
        Boolean pinned,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
