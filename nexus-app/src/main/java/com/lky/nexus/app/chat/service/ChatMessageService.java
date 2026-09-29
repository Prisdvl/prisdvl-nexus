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
     * 更新一条助手消息（流式生成过程中与结束时调用）。
     *
     * <p>与追加的区别：<b>不会</b>再次增加会话的 messageCount —— 那条消息在流开始时就已经计入。
     * 若这里也自增，会话计数会随分片多次膨胀。
     */
    ChatMessagePO updateAssistantMessage(Long userId, Long messageId, AssistantReplyDraft draft);

    /**
     * 分页查询某会话的消息。
     *
     * <p>按时间正序返回（最早在前）：聊天界面渲染需要这个顺序，
     * 前端要展示最新一页时请求最后一页即可（PageResult 里有 pages）。
     */
    PageResult<ChatMessageResponse> listMessages(Long userId, Long conversationId, int pageNum, int pageSize);
}
