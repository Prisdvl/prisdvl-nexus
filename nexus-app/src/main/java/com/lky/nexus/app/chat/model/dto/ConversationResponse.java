package com.lky.nexus.app.chat.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话视图（列表与详情共用）。
 *
 * <p>{@code id} 是 {@code Long} → 全局序列化为字符串（ADR-017）；
 * {@code messageCount} 用 {@code Integer} → 保持数字形态。
 *
 * <p>{@code kbIds}（Phase 3.4）：RAG 会话绑定的知识库。空列表表示未绑定
 * （<b>不是 null</b>）：前端不必为空值判断写分支，而"未绑定"这件事在前端是要提示用户的
 * ——绑定为空时发起提问，服务端会明确报错。
 *
 * <p>{@code agentId}（Phase 4.1）：AGENT 会话选用的 Agent；{@code null} 表示用内置默认 Agent
 * （语义见 {@code ChatConversationPO.agentId}）。与 {@code kbIds} 不同，这里允许 null——
 * "没选 Agent"是完全正常的状态，不是待办。
 */
public record ConversationResponse(
        Long id,
        String title,
        String mode,
        String modelRole,
        Integer messageCount,
        LocalDateTime lastMessageAt,
        Boolean pinned,
        List<Long> kbIds,
        Long agentId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
