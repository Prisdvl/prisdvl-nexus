package com.lky.nexus.knowledge.service;

import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.knowledge.model.dto.CreateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.dto.KnowledgeBaseResponse;
import com.lky.nexus.knowledge.model.dto.UpdateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;

/**
 * 知识库服务。
 *
 * <p>与聊天侧一致：第一个参数永远是从 JWT 取到的 {@code userId}，
 * 由调用方显式传入而不是在实现里读 SecurityContext——这样 Phase 7 的 MCP Server
 * 可以复用同一套实现（它没有 Spring Security 上下文）。
 */
public interface KnowledgeBaseService {

    /**
     * 建库。
     *
     * <p>{@code workspaceId} 由 app 层解析后传入（知识库属于哪个工作区）。
     * knowledge 模块不依赖 app 的工作区服务，因此归属 id 只能由调用方给。
     */
    KnowledgeBaseResponse create(Long userId, Long workspaceId, CreateKnowledgeBaseRequest request);

    PageResult<KnowledgeBaseResponse> list(Long userId, int pageNum, int pageSize);

    KnowledgeBaseResponse detail(Long userId, Long knowledgeBaseId);

    KnowledgeBaseResponse update(Long userId, Long knowledgeBaseId, UpdateKnowledgeBaseRequest request);

    /** 删除知识库：软删知识库本体，并物理清理其文档与标签。 */
    void delete(Long userId, Long knowledgeBaseId);

    /**
     * 校验归属并返回实体（供导入流水线复用）。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 3001
     */
    KnowledgeBasePO requireOwned(Long userId, Long knowledgeBaseId);
}
