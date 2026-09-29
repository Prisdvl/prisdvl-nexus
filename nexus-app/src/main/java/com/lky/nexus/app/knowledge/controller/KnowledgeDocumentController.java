package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.model.dto.ChunkView;
import com.lky.nexus.knowledge.model.dto.DocumentSummary;
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

    public KnowledgeDocumentController(DocumentQueryService documentQueryService) {
        this.documentQueryService = documentQueryService;
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
}
