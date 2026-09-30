package com.lky.nexus.app.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.MessageRole;
import com.lky.nexus.app.chat.model.MessageStatus;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.ChatMessageResponse;
import com.lky.nexus.app.chat.model.dto.MessageToolCall;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    private static final Logger log = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    private static final int AUTO_TITLE_LENGTH = 30;

    /** metadata JSON 里工具痕迹的字段名（与前端约定，改这里等于改协议）。 */
    private static final String TOOL_CALLS_FIELD = "toolCalls";

    private final ChatMessageMapper messageMapper;
    private final ChatConversationMapper conversationMapper;
    private final ConversationService conversationService;
    private final JsonMapper jsonMapper;

    public ChatMessageServiceImpl(ChatMessageMapper messageMapper,
                                 ChatConversationMapper conversationMapper,
                                 ConversationService conversationService,
                                 JsonMapper jsonMapper) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.conversationService = conversationService;
        this.jsonMapper = jsonMapper;
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
        // 流式场景下先落一条 STREAMING 骨架（此时还没有内容），因此这种状态不校验内容非空
        boolean streaming = MessageStatus.STREAMING.name().equals(draft.status());
        if (!streaming && !StringUtils.hasText(draft.content()) && !StringUtils.hasText(draft.errorMsg())) {
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
        message.setMetadata(toMetadata(draft.toolCalls()));
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        touchConversation(conversation, null);
        return message;
    }

    @Override
    @Transactional
    public ChatMessagePO updateAssistantMessage(Long userId, Long messageId, AssistantReplyDraft draft) {
        ChatMessagePO message = messageMapper.selectOne(Wrappers.<ChatMessagePO>lambdaQuery()
                .eq(ChatMessagePO::getId, messageId)
                .eq(ChatMessagePO::getUserId, userId));
        if (message == null) {
            throw new BusinessException(ErrorCode.MESSAGE_NOT_FOUND);
        }
        // 局部更新：只覆盖草稿里给了值的字段。
        // 流式场景下第一次调用可能只有部分内容与状态，后续再补齐 Token / 延迟。
        if (draft.content() != null) {
            message.setContent(draft.content());
        }
        if (draft.model() != null) {
            message.setModel(draft.model());
        }
        if (draft.provider() != null) {
            message.setProvider(draft.provider());
        }
        if (draft.promptTokens() != null) {
            message.setPromptTokens(draft.promptTokens());
        }
        if (draft.completionTokens() != null) {
            message.setCompletionTokens(draft.completionTokens());
        }
        if (draft.totalTokens() != null) {
            message.setTotalTokens(draft.totalTokens());
        }
        if (draft.latencyMs() != null) {
            message.setLatencyMs(draft.latencyMs());
        }
        if (draft.firstTokenMs() != null) {
            message.setFirstTokenMs(draft.firstTokenMs());
        }
        if (draft.finishReason() != null) {
            message.setFinishReason(draft.finishReason());
        }
        if (draft.status() != null) {
            message.setStatus(draft.status());
        }
        if (draft.errorMsg() != null) {
            message.setErrorMsg(draft.errorMsg());
        }
        if (draft.toolCalls() != null) {
            message.setMetadata(toMetadata(draft.toolCalls()));
        }
        // 刻意不调用 touchConversation：这条消息在流开始时已经计入 messageCount，
        // 否则"先插 STREAMING 再更新"会让计数翻倍。
        messageMapper.updateById(message);
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
                message.getCreatedAt(),
                parseToolCalls(message.getMetadata()));
    }

    /**
     * 工具痕迹 → metadata JSON。
     *
     * <p>序列化失败时返回 null 而不是抛异常：留档是<b>附加价值</b>，
     * 不能因为它失败就让一条已经生成好的回答落不了库。
     */
    private String toMetadata(List<MessageToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(Map.of(TOOL_CALLS_FIELD, toolCalls));
        } catch (Exception ex) {
            log.warn("工具调用痕迹序列化失败，本次不留档：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * metadata JSON → 工具痕迹。解析失败按空列表处理（老消息、脏数据都不该让列表接口报错）。
     *
     * <p><b>为什么要多剥一层字符串节点</b>——H2 与 MySQL 对 {@code JSON} 列的处理不同，
     * 这是实测踩到的（测试用例曾因此读到空列表，而库里明明有数据）：
     * <ul>
     *   <li>MySQL：写入 Java 字符串时，服务端把它<b>当作 JSON 文本解析</b>，存成对象；</li>
     *   <li>H2（测试 profile 实际走的是 MySQL 迁移脚本，metadata 是 JSON 列）：
     *       把字符串<b>当作字符串值序列化</b>，于是读出来是 {@code "{\"toolCalls\":…}"}
     *       —— 一个内容为 JSON 的字符串节点。</li>
     * </ul>
     * 两种都接受，前端拿到的都是同一份数据。
     */
    private List<MessageToolCall> parseToolCalls(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return List.of();
        }
        try {
            JsonNode root = jsonMapper.readTree(metadata);
            if (root.isString()) {
                root = jsonMapper.readTree(root.asString());
            }
            JsonNode node = root.path(TOOL_CALLS_FIELD);
            if (!node.isArray()) {
                return List.of();
            }
            return jsonMapper.readValue(node.toString(), new TypeReference<List<MessageToolCall>>() {
            });
        } catch (Exception ex) {
            log.warn("消息 metadata 解析失败，按无工具痕迹处理：{}", ex.getMessage());
            return List.of();
        }
    }
}
