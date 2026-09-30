package com.lky.nexus.app.chat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.ChatMode;
import com.lky.nexus.app.chat.model.dto.ConversationResponse;
import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.model.dto.UpdateConversationRequest;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
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

/**
 * 会话服务实现。
 *
 * <p>三个值得注意的设计：
 * <ol>
 *   <li><b>多租户隔离写在 SQL 条件里</b>（{@code user_id = ?}），而不是先查出来再在 Java 里比对。
 *       后者容易在新增查询时漏掉，前者泄漏可能性更低；</li>
 *   <li><b>访问别人的会话返回 404 而不是 403</b>：403 会告诉攻击者"这个 id 确实存在"；</li>
 *   <li><b>删除 = 软删会话 + 物理删消息</b>：会话要保留（用户可能只是清理列表），
 *       消息没有独立价值、且量大，物理删除避免无主数据膨胀。</li>
 * </ol>
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    /** 自动生成标题时截取的字符数。 */
    private static final int AUTO_TITLE_LENGTH = 30;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;

    public ConversationServiceImpl(ChatConversationMapper conversationMapper,
                                   ChatMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Override
    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        ChatMode mode = ChatMode.parse(request.mode());
        String title = normalizeTitle(request.title());

        ChatConversationPO conversation = new ChatConversationPO();
        conversation.setUserId(userId);
        conversation.setTitle(title);
        // title 为空说明要靠首轮消息自动生成
        conversation.setTitleAuto(!StringUtils.hasText(title));
        conversation.setMode(mode.name());
        // 记下本会话使用的模型角色：换模型时历史会话仍能追溯到当初用的是哪个角色。
        // Agent 模式用专门的 chat.agent 角色——工具调用对模型能力的要求与普通对话不同，
        // 两条链路分开配置才能在"日常对话用便宜模型、Agent 用强模型"之间取舍。
        conversation.setModelRole(mode == ChatMode.AGENT
                ? AiModelProperties.Role.CHAT_AGENT
                : AiModelProperties.Role.CHAT_DEFAULT);
        conversation.setMessageCount(0);
        conversation.setPinned(false);
        LocalDateTime now = LocalDateTime.now();
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        conversationMapper.insert(conversation);
        log.info("创建会话 id={}, userId={}, mode={}", conversation.getId(), userId, mode);
        return toResponse(conversation);
    }

    @Override
    public PageResult<ConversationResponse> list(Long userId, int pageNum, int pageSize) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);

        Page<ChatConversationPO> page = new Page<>(safePageNum, safePageSize);
        Page<ChatConversationPO> result = conversationMapper.selectPage(page,
                Wrappers.<ChatConversationPO>lambdaQuery()
                        .eq(ChatConversationPO::getUserId, userId)
                        // 置顶优先，其次按最后消息时间；都没有消息时按创建顺序兜底
                        .orderByDesc(ChatConversationPO::getPinned)
                        .orderByDesc(ChatConversationPO::getLastMessageAt)
                        .orderByDesc(ChatConversationPO::getId));

        List<ConversationResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), safePageNum, safePageSize);
    }

    @Override
    public ConversationResponse detail(Long userId, Long conversationId) {
        return toResponse(requireOwned(userId, conversationId));
    }

    @Override
    @Transactional
    public ConversationResponse update(Long userId, Long conversationId, UpdateConversationRequest request) {
        ChatConversationPO conversation = requireOwned(userId, conversationId);

        if (request.title() != null) {
            String title = request.title().trim();
            if (!StringUtils.hasText(title)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "标题不能为空字符串");
            }
            conversation.setTitle(title);
            // 用户手动改过标题后，后续对话不再自动覆盖
            conversation.setTitleAuto(false);
        }
        if (request.pinned() != null) {
            conversation.setPinned(request.pinned());
        }
        conversation.setUpdatedAt(LocalDateTime.now());

        conversationMapper.updateById(conversation);
        return toResponse(conversation);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);

        // 会话走逻辑删除（@TableLogic 自动改写为 UPDATE deleted=1）
        conversationMapper.deleteById(conversationId);
        // 消息物理删除：否则消息表会随会话删除不断堆积无主数据
        int removedMessages = messageMapper.delete(Wrappers.<ChatMessagePO>lambdaQuery()
                .eq(ChatMessagePO::getConversationId, conversationId));
        log.info("删除会话 id={}, userId={}, 同时清理消息 {} 条", conversationId, userId, removedMessages);
    }

    @Override
    public ChatConversationPO requireOwned(Long userId, Long conversationId) {
        if (conversationId == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        // 关键：把 user_id 放进查询条件，而不是查出来再比对
        ChatConversationPO conversation = conversationMapper.selectOne(
                Wrappers.<ChatConversationPO>lambdaQuery()
                        .eq(ChatConversationPO::getId, conversationId)
                        .eq(ChatConversationPO::getUserId, userId));
        if (conversation == null) {
            // 别人的会话与不存在的会话返回完全相同的错误：不泄露资源存在性
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private String normalizeTitle(String rawTitle) {
        return StringUtils.hasText(rawTitle) ? rawTitle.trim() : null;
    }

    private ConversationResponse toResponse(ChatConversationPO conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getMode(),
                conversation.getModelRole(),
                conversation.getMessageCount(),
                conversation.getLastMessageAt(),
                conversation.getPinned(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
