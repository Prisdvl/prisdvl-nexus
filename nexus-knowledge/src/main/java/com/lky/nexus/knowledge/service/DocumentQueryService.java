package com.lky.nexus.knowledge.service;

import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.knowledge.model.dto.ChunkView;
import com.lky.nexus.knowledge.model.dto.DocumentDetail;
import com.lky.nexus.knowledge.model.dto.DocumentSummary;

import java.util.List;

/**
 * 文档与切片的查询服务（只读）。
 *
 * <p>与导入/向量化那些"写入侧"服务分开：查询接口是前端最频繁调用的，
 * 不应该和带副作用的流程混在一起——否则很容易在某个查询里不小心触发了重建索引之类的动作。
 */
public interface DocumentQueryService {

    /** 分页查询知识库下的文档。 */
    PageResult<DocumentSummary> listDocuments(Long userId, Long knowledgeBaseId, int pageNum, int pageSize);

    /** 查询某篇文档的切片（按序号）。 */
    List<ChunkView> listChunks(Long userId, Long knowledgeBaseId, Long documentId);

    /**
     * 查询文档详情（含原始正文与大纲）。
     *
     * <p>正文从落盘的原始文件读回，而不是用切片拼——切片带标题路径前缀且按检索边界切分，
     * 拼出来既会重复标题、也会让段落断裂。
     */
    DocumentDetail getDocument(Long userId, Long knowledgeBaseId, Long documentId);
}
