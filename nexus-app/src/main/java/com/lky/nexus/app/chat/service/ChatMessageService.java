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

    /**
     * 追加一条助手消息（Step 1.6 的 AI 链路调用）。
     *
     * <p>{@code agentId} 是<b>这条消息的出处</b>（5.4）：AGENT 模式下由哪个 Agent 跑出来的，
     * 非 AGENT 模式传 {@code null}。它是插入即定、永不改变的身份事实，因此走参数而<b>不进</b>
     * {@link AssistantReplyDraft}——那个对象的字段集实际定义了"哪些字段能被 update 覆盖"
     * （见 {@link #updateAssistantMessage}），把 agentId 放进去会让它读起来像可以被改写。
     *
     * <p>调用方要传的是<b>解析之后的具体 id</b>（含内置默认那一行），而不是会话上的
     * {@code agentId}：后者可能为 null（"没选"），照抄下来就丢掉了"其实是内置默认在跑"。
     */
    ChatMessagePO appendAssistantMessage(Long userId, Long conversationId, Long agentId,
                                         AssistantReplyDraft draft);

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
