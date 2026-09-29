package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 索引与检索接口。
 *
 * <p>目前检索只开放关键词（BM25）一路，用途有两个：
 * ① 让"导入 → 建索引 → 检索"这条链路现在就能被验证与使用；
 * ② 为 Phase 3.6 的混合检索提供单路对照（调融合权重时必须能单独看每一路的表现）。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeSearchController {

    private static final int MAX_BATCH_LIMIT = 2000;
    private static final int MAX_TOP_K = 100;

    private final ChunkIndexService chunkIndexService;
    private final ChunkSearchService chunkSearchService;

    public KnowledgeSearchController(ChunkIndexService chunkIndexService,
                                     ChunkSearchService chunkSearchService) {
        this.chunkIndexService = chunkIndexService;
        this.chunkSearchService = chunkSearchService;
    }

    /** 为待索引的切片建立检索索引（幂等，可重复调用）。 */
    @PostMapping("/{knowledgeBaseId}/index")
    public Result<ChunkIndexService.IndexResult> index(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(defaultValue = "500") int batchLimit) {
        int limit = Math.clamp(batchLimit, 1, MAX_BATCH_LIMIT);
        return Result.ok(chunkIndexService.indexPendingChunks(CurrentUser.requireId(), knowledgeBaseId, limit));
    }

    /** 重建索引：新建物理索引 → 原子切换别名 → 旧索引转为待重建。 */
    @PostMapping("/{knowledgeBaseId}/index/recreate")
    public Result<Void> recreate(@PathVariable Long knowledgeBaseId) {
        chunkIndexService.recreateIndex(CurrentUser.requireId(), knowledgeBaseId);
        return Result.ok();
    }

    /** 关键词检索（BM25）。 */
    @GetMapping("/{knowledgeBaseId}/search")
    public Result<List<ChunkSearchService.SearchHit>> search(
            @PathVariable Long knowledgeBaseId,
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        int limit = Math.clamp(topK, 1, MAX_TOP_K);
        return Result.ok(chunkSearchService.searchBm25(CurrentUser.requireId(), knowledgeBaseId, q, limit));
    }
}
