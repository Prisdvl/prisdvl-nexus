package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.model.dto.ChunkView;
import com.lky.nexus.knowledge.model.dto.DocumentDetail;
import com.lky.nexus.knowledge.model.dto.DocumentSummary;
import com.lky.nexus.knowledge.service.DocumentLinkService;
import com.lky.nexus.knowledge.service.DocumentQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档与切片查询接口（前端浏览用）。
 */
@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeDocumentController {

    private final DocumentQueryService documentQueryService;
    private final DocumentLinkService documentLinkService;

    public KnowledgeDocumentController(DocumentQueryService documentQueryService,
                                       DocumentLinkService documentLinkService) {
        this.documentQueryService = documentQueryService;
        this.documentLinkService = documentLinkService;
    }

    /** 相关推荐（Phase 4.2）：共同标签 + 链接关系加权。 */
    @GetMapping("/documents/{documentId}/related")
    public Result<List<DocumentLinkService.RelatedDocument>> related(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "5") int limit) {
        return Result.ok(documentLinkService.related(
                CurrentUser.requireId(), knowledgeBaseId, documentId, limit));
    }

    /** 文档列表。 */
    @GetMapping("/documents")
    public Result<PageResult<DocumentSummary>> listDocuments(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        return Result.ok(documentQueryService.listDocuments(
                CurrentUser.requireId(), knowledgeBaseId, pageNum, pageSize));
    }

    /** 某篇文档的切片（含向量/索引状态，便于排查"为什么搜不到"）。 */
    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<ChunkView>> listChunks(@PathVariable Long knowledgeBaseId,
                                             @PathVariable Long documentId) {
        return Result.ok(documentQueryService.listChunks(
                CurrentUser.requireId(), knowledgeBaseId, documentId));
    }

    /** 文档详情：原始正文（供 Markdown 渲染）+ 大纲（供目录跳转）。 */
    @GetMapping("/documents/{documentId}")
    public Result<DocumentDetail> getDocument(@PathVariable Long knowledgeBaseId,
                                             @PathVariable Long documentId) {
        return Result.ok(documentQueryService.getDocument(
                CurrentUser.requireId(), knowledgeBaseId, documentId));
    }

    /** 文档关联（Phase 4）：出链 + 反向链接 + 待创建的引用数。 */
    @GetMapping("/documents/{documentId}/links")
    public Result<DocumentLinkService.DocumentLinks> links(@PathVariable Long knowledgeBaseId,
                                                          @PathVariable Long documentId) {
        return Result.ok(documentLinkService.links(
                CurrentUser.requireId(), knowledgeBaseId, documentId));
    }
}
