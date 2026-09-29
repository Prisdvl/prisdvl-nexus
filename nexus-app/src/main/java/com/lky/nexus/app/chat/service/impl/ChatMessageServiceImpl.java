package com.lky.nexus.app.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.MessageRole;
import com.lky.nexus.app.chat.model.MessageStatus;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.ChatMessageResponse;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息服务实现。
 *
 * <p>两个关键点：
 * <ol>
 *   <li><b>会话计数用 SQL 自增</b>（{@code message_count = message_count + 1}），
 *       而不是"读出来 +1 再写回"。后者在并发下会丢失更新；
 *       虽然个人项目并发很低，但这是不需要付出代价就能做对的事。</li>
 *   <li><b>消息排序按主键（雪花 ID）升序</b>：雪花 ID 的高位是时间戳，
 *       因此 ID 顺序天然等于时间顺序，不必额外按 created_at 排序（少一个索引，少一次比较）。</li>
 * </ol>
 */
@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final int AUTO_TITLE_LENGTH = 30;

    private final ChatMessageMapper messageMapper;
    private final ChatConversationMapper conversationMapper;
    private final ConversationService conversationService;

    public ChatMessageServiceImpl(ChatMessageMapper messageMapper,
                                 ChatConversationMapper conversationMapper,
                                 ConversationService conversationService) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.conversationService = conversationService;
    }

    @Override
    @Transactional
    public ChatMessagePO appendUserMessage(Long userId, Long conversationId, String content, String requestId) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "消息内容不能为空");
        }
        ChatConversationPO conversation = conversationService.requireOwned(userId, conversationId);

        ChatMessagePO message = new ChatMessagePO();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(MessageRole.USER.name());
        message.setContent(content);
        message.setStatus(MessageStatus.DONE.name());
        message.setRequestId(requestId);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        // 首轮用户消息可以用来生成标题
        touchConversation(conversation, content);
        return message;
    }

    @Override
    @Transactional
    public ChatMessagePO appendAssistantMessage(Long userId, Long conversationId, AssistantReplyDraft draft) {
        ChatConversationPO conversation = conversationService.requireOwned(userId, conversationId);
        if (!StringUtils.hasText(draft.content()) && !StringUtils.hasText(draft.errorMsg())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "助手消息既没有内容也没有错误信息");
        }

        ChatMessagePO message = new ChatMessagePO();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(MessageRole.ASSISTANT.name());
        message.setContent(draft.content());
        message.setModel(draft.model());
        message.setProvider(draft.provider());
        message.setPromptTokens(draft.promptTokens());
        message.setCompletionTokens(draft.completionTokens());
        message.setTotalTokens(draft.totalTokens());
        message.setLatencyMs(draft.latencyMs());
        message.setFirstTokenMs(draft.firstTokenMs());
        message.setFinishReason(draft.finishReason());
        message.setRequestId(draft.requestId());
        message.setErrorMsg(draft.errorMsg());
        message.setStatus(StringUtils.hasText(draft.status()) ? draft.status() : MessageStatus.DONE.name());
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        touchConversation(conversation, null);
        return message;
    }

    @Override
    public PageResult<ChatMessageResponse> listMessages(Long userId, Long conversationId, int pageNum, int pageSize) {
        conversationService.requireOwned(userId, conversationId);

        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);

        Page<ChatMessagePO> page = new Page<>(safePageNum, safePageSize);
        Page<ChatMessagePO> result = messageMapper.selectPage(page,
                Wrappers.<ChatMessagePO>lambdaQuery()
                        .eq(ChatMessagePO::getConversationId, conversationId)
                        .eq(ChatMessagePO::getUserId, userId)
                        .orderByAsc(ChatMessagePO::getId));

        List<ChatMessageResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), safePageNum, safePageSize);
    }

    /**
     * 维护会话的冗余字段。
     *
     * @param firstUserContent 非 null 表示这是一条用户消息，可能用于自动生成标题
     */
    private void touchConversation(ChatConversationPO conversation, String firstUserContent) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<ChatConversationPO> update = Wrappers.<ChatConversationPO>lambdaUpdate()
                .eq(ChatConversationPO::getId, conversation.getId())
                // 用 SQL 自增而不是读改写：并发下不会丢失更新
                .setSql("message_count = message_count + 1")
                .set(ChatConversationPO::getLastMessageAt, now)
                .set(ChatConversationPO::getUpdatedAt, now);

        if (firstUserContent != null
                && Boolean.TRUE.equals(conversation.getTitleAuto())
                && !StringUtils.hasText(conversation.getTitle())) {
            update.set(ChatConversationPO::getTitle, autoTitle(firstUserContent))
                    .set(ChatConversationPO::getTitleAuto, false);
        }
        conversationMapper.update(null, update);
    }

    /** 用首条用户消息生成标题：去掉换行、截断到 30 字。 */
    private String autoTitle(String content) {
        String flattened = content.replaceAll("\\s+", " ").trim();
        return flattened.length() <= AUTO_TITLE_LENGTH
                ? flattened
                : flattened.substring(0, AUTO_TITLE_LENGTH);
    }

    private ChatMessageResponse toResponse(ChatMessagePO message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getModel(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getLatencyMs(),
                message.getFirstTokenMs(),
                message.getStatus(),
                message.getErrorMsg(),
                message.getCreatedAt());
    }
}
