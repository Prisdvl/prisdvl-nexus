package com.lky.nexus.app.chat.service;

import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.ChatMessageResponse;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.common.api.PageResult;

/**
 * 消息服务：完整聊天历史（Chat History）的读写，与 Spring AI 的 ChatMemory 职责分离（ADR-011）。
 */
public interface ChatMessageService {

    /** 追加一条用户消息；同时维护会话的 messageCount / lastMessageAt，必要时自动生成标题。 */
    ChatMessagePO appendUserMessage(Long userId, Long conversationId, String content, String requestId);

    /** 追加一条助手消息（Step 1.6 的 AI 链路调用）。 */
    ChatMessagePO appendAssistantMessage(Long userId, Long conversationId, AssistantReplyDraft draft);

    /**
     * 分页查询某会话的消息。
     *
     * <p>按时间正序返回（最早在前）：聊天界面渲染需要这个顺序，
     * 前端要展示最新一页时请求最后一页即可（PageResult 里有 pages）。
     */
    PageResult<ChatMessageResponse> listMessages(Long userId, Long conversationId, int pageNum, int pageSize);
}
