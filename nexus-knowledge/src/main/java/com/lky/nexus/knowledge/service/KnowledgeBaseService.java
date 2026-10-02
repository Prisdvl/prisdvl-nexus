package com.lky.nexus.knowledge.service;

import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.knowledge.model.dto.CreateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.dto.KnowledgeBaseResponse;
import com.lky.nexus.knowledge.model.dto.UpdateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;

import java.util.List;

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

    /**
     * 批量校验归属（多知识库检索用）。
     *
     * <p>与 {@link #requireOwned} 的语义完全一致，只是把"一个"换成"一组"：
     * 任意一个 id 不存在或不属于该用户，整批就失败。**不做部分成功**——
     * 静默跳过查不到的库，会让"我明明绑了三个库，怎么只搜到两个"变成一个没有报错的谜。
     *
     * <p>返回值按 <b>入参顺序</b>排列（不是数据库的返回顺序），去重但保序：
     * 同一库绑两次只算一次，否则会白跑一轮检索并在结果里留下重复片段。
     * 入参为 null 或空返回空列表，而不是抛异常——"没绑库"由调用方决定是报错还是降级。
     *
     * <p>失败时错误信息带上那个 id：多库场景下用户面对一屏会话配置，
     * 只被告知"知识库不存在"而无从知道是哪个。<b>注意措辞对
     * "不存在"与"存在但无权访问"必须完全一致</b>（沿用 3001 → 404 的既有口径），
     * 否则等于用错误信息泄漏了别人库的存在性。
     *
     * @throws com.lky.nexus.common.exception.BusinessException 3001
     */
    List<KnowledgeBasePO> requireAllOwned(Long userId, List<Long> knowledgeBaseIds);
}
