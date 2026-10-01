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

    /**
     * 删除知识库：知识库与文档软删，切片 / 关联边 / 标签物理清理，
     * 并清空向量库与检索索引里该库的全部数据、删除磁盘上的原始文件。
     *
     * <p>外部清理（向量库 / 检索索引）放在最前面且失败即整体失败：它们不在本事务内、
     * 无法回滚，一旦留下就是"检索命中却翻不出文档"的孤儿。详见
     * {@code DocumentPersistService#delete}。
     */
    void delete(Long userId, Long knowledgeBaseId);

    /**
     * 校验归属并返回实体（供导入流水线复用）。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 3001
     */
    KnowledgeBasePO requireOwned(Long userId, Long knowledgeBaseId);
}
