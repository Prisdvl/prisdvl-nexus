package com.lky.nexus.app.chat.model.dto;

/**
 * 一次工具调用的留档（Agent 模式）。
 *
 * <p>为什么落库：工具调用痕迹是"用户可控"的核心证据——用户要能回看"当时到底检索了什么、
 * 命中了多少条"。只在前端内存里存的话，一刷新就没了，等于没留。
 *
 * <p>存在 {@code chat_message.metadata} 这一列里（JSON），不额外开表：
 * 它是消息的附属信息，生命周期与消息完全一致，拆表只会多一次 join。
 */
public record MessageToolCall(String tool, String input, String resultSummary) {
}
