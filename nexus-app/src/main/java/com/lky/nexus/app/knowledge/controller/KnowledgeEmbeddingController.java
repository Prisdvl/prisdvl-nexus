package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.service.ChunkEmbeddingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向量化（重建索引）接口。
 *
 * <p>为什么向量化要做成独立入口，而不是"导入完成自动跑"：
 * <ul>
 *   <li>它依赖外部模型（本机 Ollama），失败原因与导入完全不同，分开才好排查；</li>
 *   <li>换嵌入模型、补失败批次、重建索引都需要"只跑向量化"的能力；</li>
 *   <li>它天然是幂等的（只处理 {@code vector_status='PENDING'} 的切片），重复调用安全。</li>
 * </ul>
 *
 * <p><b>当前是同步执行</b>：一次最多处理 {@code batchLimit} 条（默认 200），
 * 避免单个 HTTP 请求长时间占用线程。与导入一样，后续会改成"投递消息 + 进度查询"。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeEmbeddingController {

    /** 单次请求的上限：防止误传一个大值把请求挂死。 */
    private static final int MAX_BATCH_LIMIT = 1000;

    private final ChunkEmbeddingService chunkEmbeddingService;

    public KnowledgeEmbeddingController(ChunkEmbeddingService chunkEmbeddingService) {
        this.chunkEmbeddingService = chunkEmbeddingService;
    }

    /** 为知识库中待处理的切片生成向量。 */
    @PostMapping("/{knowledgeBaseId}/embed")
    public Result<ChunkEmbeddingService.EmbeddingResult> embed(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(defaultValue = "200") int batchLimit) {
        int limit = Math.clamp(batchLimit, 1, MAX_BATCH_LIMIT);
        return Result.ok(chunkEmbeddingService.embedPendingChunks(
                CurrentUser.requireId(), knowledgeBaseId, limit));
    }
}
