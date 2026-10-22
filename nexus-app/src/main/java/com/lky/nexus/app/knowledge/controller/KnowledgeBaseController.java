package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.workspace.WorkspaceService;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.model.dto.CreateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.dto.KnowledgeBaseResponse;
import com.lky.nexus.knowledge.model.dto.UpdateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库接口。
 *
 * <p>Controller 只做三件事：取 userId（从 JWT）、接参数、包 Result；
 * 业务规则与租户隔离都在 {@code nexus-knowledge} 的服务层（对应 docs/architecture.md §3 的分层红线）。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final WorkspaceService workspaceService;
    /**
     * 只用来给详情接口补「索引覆盖」（切片总数 / 已入索引数 / 最近建索引时间）。
     *
     * <p>不在 {@code KnowledgeBaseServiceImpl} 里算：那边为了避开构造器循环依赖，
     * 刻意只依赖 {@code VectorStore} / {@code ChunkIndex} 端口，不依赖 {@code ChunkIndexService}。
     */
    private final ChunkIndexService chunkIndexService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                   WorkspaceService workspaceService,
                                   ChunkIndexService chunkIndexService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.workspaceService = workspaceService;
        this.chunkIndexService = chunkIndexService;
    }

    /** 新建知识库；workspace 由 app 层解析为本人个人空间（knowledge 模块不依赖工作区服务）。 */
    @PostMapping
    public Result<KnowledgeBaseResponse> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        // 平台化 Phase 2：知识库归属 workspace。workspaceId 由 app 层解析
        // （knowledge 模块不依赖 app 的工作区服务，故由 Controller 传入）。
        Long userId = CurrentUser.requireId();
        Long workspaceId = workspaceService.requirePersonalWorkspace(userId);
        return Result.ok(knowledgeBaseService.create(userId, workspaceId, request));
    }

    /** 列出当前用户的知识库（分页）。 */
    @GetMapping
    public Result<PageResult<KnowledgeBaseResponse>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(knowledgeBaseService.list(CurrentUser.requireId(), pageNum, pageSize));
    }

    /** 查看单个知识库详情（非本人返回 404）；附带索引覆盖，供界面判断是否还没建索引。 */
    @GetMapping("/{knowledgeBaseId}")
    public Result<KnowledgeBaseResponse> detail(@PathVariable Long knowledgeBaseId) {
        Long userId = CurrentUser.requireId();
        KnowledgeBaseResponse detail = knowledgeBaseService.detail(userId, knowledgeBaseId);
        ChunkIndexService.IndexCoverage coverage = chunkIndexService.coverage(userId, knowledgeBaseId);
        return Result.ok(detail.withIndex(
                Math.toIntExact(coverage.indexedChunkCount()),
                coverage.lastIndexedAt()));
    }

    /** 更新知识库（非本人返回 404）。 */
    @PutMapping("/{knowledgeBaseId}")
    public Result<KnowledgeBaseResponse> update(@PathVariable Long knowledgeBaseId,
                                               @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return Result.ok(knowledgeBaseService.update(CurrentUser.requireId(), knowledgeBaseId, request));
    }

    /** 删除知识库（非本人返回 404，归属校验在服务层）。 */
    @DeleteMapping("/{knowledgeBaseId}")
    public Result<Void> delete(@PathVariable Long knowledgeBaseId) {
        knowledgeBaseService.delete(CurrentUser.requireId(), knowledgeBaseId);
        return Result.ok();
    }
}
