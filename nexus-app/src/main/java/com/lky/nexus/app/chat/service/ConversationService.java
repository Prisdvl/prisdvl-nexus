package com.lky.nexus.app.chat.service;

import com.lky.nexus.app.chat.model.dto.ConversationResponse;
import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.model.dto.UpdateConversationRequest;
import com.lky.nexus.common.api.PageResult;

/**
 * 会话服务。
 *
 * <p>所有方法的第一个参数都是 {@code userId}，且必须由调用方从 JWT 的 sub 取得
 * （见 {@code CurrentUser}）。把它作为显式参数而不是在实现里读 SecurityContext，
 * 是为了让这一层可以被非 Web 场景复用（例如 Phase 7 的 MCP Server、定时清理任务）。
 */
public interface ConversationService {

    ConversationResponse create(Long userId, CreateConversationRequest request);

    PageResult<ConversationResponse> list(Long userId, int pageNum, int pageSize);

    ConversationResponse detail(Long userId, Long conversationId);

    ConversationResponse update(Long userId, Long conversationId, UpdateConversationRequest request);

    /** 删除会话（软删会话 + 物理清理其消息）。 */
    void delete(Long userId, Long conversationId);

    /**
     * 校验会话归属并返回实体，供消息服务复用。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 8001（不存在或不属于当前用户）
     */
    com.lky.nexus.app.chat.model.entity.ChatConversationPO requireOwned(Long userId, Long conversationId);
}
