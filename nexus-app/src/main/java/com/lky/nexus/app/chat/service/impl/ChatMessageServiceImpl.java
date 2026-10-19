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
import com.lky.nexus.knowledge.rag.RagAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    /** metadata JSON 里引用明细的字段名（与前端约定，改这里等于改协议）。 */
    private static final String CITATIONS_FIELD = "citations";

    /** metadata JSON 里检索命中明细的字段名（与前端约定，改这里等于改协议）。 */
    private static final String RETRIEVAL_FIELD = "retrieval";

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
    public ChatMessagePO appendAssistantMessage(Long userId, Long conversationId, Long agentId,
                                                AssistantReplyDraft draft) {
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
        // 出处（5.4）：非 AGENT 模式为 null，如实表达"不是任何 Agent 产出的"
        message.setAgentId(agentId);
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
        message.setMetadata(toMetadata(draft.toolCalls(), draft.citations(), draft.retrieval()));
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
        //
        // 【这里刻意没有 agent_id】它不是可更新字段，而是这条消息的出处（5.4）：
        // 插入时定下、之后任何一次 update 都不该动它。想换 Agent 就是重新生成一条新消息，
        // 那时新消息自己带新的 agent_id——把旧的改掉等于篡改历史（"这条回答当时是谁跑的"就没答案了）。
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
        // 痕迹与引用共用 metadata 这一列：任意一个非 null 就整列重写。
        // 只写其中一个会把另一个抹掉——而流式落库时两者是同时拿到的（见 ChatStreamServiceImpl.persist），
        // 所以"只给了一半"只可能来自别的调用方，那时按"这一半就是全部"处理才是对的
        if (draft.toolCalls() != null || draft.citations() != null || draft.retrieval() != null) {
            message.setMetadata(toMetadata(draft.toolCalls(), draft.citations(), draft.retrieval()));
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
                // 出处（5.4）：非 AGENT 模式或老消息为 null，界面据此决定显不显示
                message.getAgentId(),
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
                parseToolCalls(message.getMetadata()),
                parseCitations(message.getMetadata()),
                parseRetrieval(message.getMetadata()));
    }

    /**
     * 工具痕迹 + 引用 → metadata JSON。
     *
     * <p>两者共用一列（{@code chat_message.metadata}）而不是各占一列：它们都是
     * "这条回答的附属事实"，列数不该随附属事实的个数增长；而两列会让"读到一半"成为可能
     * （只更新了工具痕迹那一列，引用那列还是上一轮的值）。
     *
     * <p>序列化失败时返回 null 而不是抛异常：留档是<b>附加价值</b>，
     * 不能因为它失败就让一条已经生成好的回答落不了库。
     */
    private String toMetadata(List<MessageToolCall> toolCalls,
                              List<RagAnswer.RagCitation> citations,
                              Map<String, Object> retrieval) {
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        boolean hasCitations = citations != null && !citations.isEmpty();
        boolean hasRetrieval = retrieval != null && !retrieval.isEmpty();
        if (!hasToolCalls && !hasCitations && !hasRetrieval) {
            return null;
        }
        try {
            // LinkedHashMap 而不是 Map.of：后者不允许 null 值，也固定不住字段顺序
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (hasToolCalls) {
                metadata.put(TOOL_CALLS_FIELD, toolCalls);
            }
            if (hasCitations) {
                metadata.put(CITATIONS_FIELD, citations);
            }
            if (hasRetrieval) {
                metadata.put(RETRIEVAL_FIELD, retrieval);
            }
            return jsonMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            log.warn("消息 metadata 序列化失败，本次不留档：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * metadata JSON → 引用明细。解析失败按空列表处理（与工具痕迹同理）。
     *
     * <p>H2 与 MySQL 对 {@code JSON} 列的差异见 {@link #parseToolCalls}。
     */
    private List<RagAnswer.RagCitation> parseCitations(String metadata) {
        JsonNode node = metadataNode(metadata, CITATIONS_FIELD);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(node.toString(), new TypeReference<List<RagAnswer.RagCitation>>() {
            });
        } catch (Exception ex) {
            log.warn("消息 metadata 里的引用解析失败，按无引用处理：{}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * metadata JSON → 检索命中明细（形状与流里的 {@code retrieval} 事件一致）。
     *
     * <p>刻意返回 {@code Map<String,Object>} 而不是新造一个类型：这份负载的产出方是
     * {@code RetrievalEvent.of(...)}，落库与推流必须是同一份形状——再造一个平行的类型，
     * 就等于给"流里发的"和"库里读的"留下分叉的机会，而分叉一旦发生，
     * 前端两套渲染逻辑里只会改一套（这正是 roadmap 待办 #6 的成因）。
     *
     * <p>解析失败返回 {@code null} 而不是空 Map：{@code null} 表达"这条消息没有检索信息"
     * （普通对话），空 Map 会让人以为"检索了但一条都没命中"（那是 {@code empty=true} 的合法负载）。
     */
    private Map<String, Object> parseRetrieval(String metadata) {
        JsonNode node = metadataNode(metadata, RETRIEVAL_FIELD);
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            return jsonMapper.readValue(node.toString(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("消息 metadata 里的检索命中明细解析失败，按无检索信息处理：{}", ex.getMessage());
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
        JsonNode node = metadataNode(metadata, TOOL_CALLS_FIELD);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(node.toString(), new TypeReference<List<MessageToolCall>>() {
            });
        } catch (Exception ex) {
            log.warn("消息 metadata 里的工具痕迹解析失败，按无痕迹处理：{}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 取 metadata 里某个字段的节点；拿不到就返回 null。
     *
     * <p>剥字符串节点那一层的原因见 {@link #parseToolCalls} 的类注释（H2 与 MySQL 对 JSON 列的差异）。
     * 抽出来是因为 metadata 现在有<b>两个</b>字段，两边各写一遍解析只会让那份差异说明
     * 有一半被漏掉。
     */
    private JsonNode metadataNode(String metadata, String field) {
        if (!StringUtils.hasText(metadata)) {
            return null;
        }
        try {
            JsonNode root = jsonMapper.readTree(metadata);
            if (root.isString()) {
                root = jsonMapper.readTree(root.asString());
            }
            return root.path(field);
        } catch (Exception ex) {
            log.warn("消息 metadata 解析失败，按空处理：{}", ex.getMessage());
            return null;
        }
    }
}
