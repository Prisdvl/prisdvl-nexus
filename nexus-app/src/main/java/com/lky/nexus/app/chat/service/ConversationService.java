package com.lky.nexus.app.chat.service;

import com.lky.nexus.app.chat.model.dto.ConversationResponse;
import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.model.dto.ForkConversationRequest;
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

    /**
     * 新建会话。
     *
     * @param userId 当前用户 id（会话归属人）
     * @param request 建会话请求：标题（可空，空则首轮消息自动生成）、模式、知识库、Agent
     * @return 新建会话的视图
     * @throws com.lky.nexus.common.exception.BusinessException 会话模式非法（1001）、
     *         绑定了不属于自己/不存在的知识库（3001）、或选了不可用 Agent（5006/5008/5009）
     */
    ConversationResponse create(Long userId, CreateConversationRequest request);

    /**
     * 分页列出当前用户的会话（按置顶、最后消息时间、id 排序）。
     *
     * @param userId 当前用户 id
     * @param pageNum 页码（从 1 开始，非法值会被规整）
     * @param pageSize 每页条数（非法值会被规整）
     * @return 会话视图分页结果
     */
    PageResult<ConversationResponse> list(Long userId, int pageNum, int pageSize);

    /**
     * 查询单个会话详情。
     *
     * @param userId 当前用户 id
     * @param conversationId 会话 id
     * @return 会话视图
     * @throws com.lky.nexus.common.exception.BusinessException 会话不存在或不属于该用户（8001）
     */
    ConversationResponse detail(Long userId, Long conversationId);

    /**
     * 局部更新会话（标题、置顶、模式、知识库、Agent）。
     *
     * <p>未传的字段不改；支持在保留历史的前提下切换模式。具体迁移规则见
     * {@link com.lky.nexus.app.chat.model.dto.UpdateConversationRequest} 的类注释。
     *
     * @param userId 当前用户 id
     * @param conversationId 会话 id
     * @param request 局部更新请求
     * @return 更新后的会话视图
     * @throws com.lky.nexus.common.exception.BusinessException 会话不存在或不属于该用户（8001）、
     *         模式非法/约束冲突（1001）、知识库越权（3001）、Agent 不可用（5006/5008/5009）
     */
    ConversationResponse update(Long userId, Long conversationId, UpdateConversationRequest request);

    /** 删除会话（软删会话 + 物理清理其消息）。 */
    void delete(Long userId, Long conversationId);

    /**
     * 分支：复制一条新会话，消息复制到 beforeMessageId（含）为止。
     *
     * <p>标题由调用方显式给出（前端按「分支 / 分支1 / …」命名）；模式、Agent 与知识库绑定
     * 与原会话一致。新会话是独立的一份数据，之后的提问互不影响。
     */
    ConversationResponse fork(Long userId, Long conversationId, ForkConversationRequest request);

    /**
     * 校验会话归属并返回实体，供消息服务复用。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 8001（不存在或不属于当前用户）
     */
    com.lky.nexus.app.chat.model.entity.ChatConversationPO requireOwned(Long userId, Long conversationId);
}
