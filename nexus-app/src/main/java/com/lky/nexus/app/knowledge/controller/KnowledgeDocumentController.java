package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.model.dto.ChunkView;
import com.lky.nexus.knowledge.model.dto.DocumentDetail;
import com.lky.nexus.knowledge.model.dto.DocumentSummary;
import com.lky.nexus.knowledge.service.DocumentLinkService;
import com.lky.nexus.knowledge.service.DocumentPersistService;
import com.lky.nexus.knowledge.service.DocumentQueryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档与切片查询接口（前端浏览用），外加一个删除入口。
 */
@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeDocumentController {

    private final DocumentQueryService documentQueryService;
    private final DocumentLinkService documentLinkService;
    private final DocumentPersistService documentPersistService;

    public KnowledgeDocumentController(DocumentQueryService documentQueryService,
                                       DocumentLinkService documentLinkService,
                                       DocumentPersistService documentPersistService) {
        this.documentQueryService = documentQueryService;
        this.documentLinkService = documentLinkService;
        this.documentPersistService = documentPersistService;
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

    /**
     * 删除一篇文档（Phase 5.3 · P1-2）。
     *
     * <p>是这个模块里唯一一个"写"的接口——其余都只是查询。放在这里而不是新开一个
     * 删除控制器：路由前缀与它操作的对象完全一致，拆开只会让前端多记一个前缀。
     *
     * <p>失败语义值得前端注意：如果向量库或检索索引清不掉，接口会返回错误，
     * <b>文档仍然留在库里</b>。这不是"部分成功"，是刻意 fail-closed——宁可删不掉让人重试，
     * 也不要在检索里留下查不到正文的孤儿（详见 {@code DocumentPersistService#delete}）。
     */
    @DeleteMapping("/documents/{documentId}")
    public Result<Void> deleteDocument(@PathVariable Long knowledgeBaseId,
                                       @PathVariable Long documentId) {
        documentPersistService.deleteDocument(
                CurrentUser.requireId(), knowledgeBaseId, documentId);
        return Result.ok();
    }
}
