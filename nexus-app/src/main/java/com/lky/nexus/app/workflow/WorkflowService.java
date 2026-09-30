package com.lky.nexus.app.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.service.ChunkEmbeddingService;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.app.workflow.WorkflowEngine.Step;
import com.lky.nexus.app.workflow.WorkflowEngine.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识工作流（Phase 6.2 第一片）：
 * 统计 + 能力探测。真实执行（向量化/建索引）在环境能力确认后再接，接口与语义已就位。
 *
 * <p>每步结果都如实记录环境事实（向量库/检索是否可用），
 * 降级不等于失败——不可用就"跳过并说明"，只有真正的错误才中止后续步骤。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final ChunkIndexService chunkIndexService;
    private final boolean vectorEnabled;
    private final boolean searchEnabled;

    public WorkflowService(KnowledgeBaseMapper knowledgeBaseMapper,
                           DocumentMapper documentMapper,
                           ChunkMapper chunkMapper,
                           KnowledgeBaseService knowledgeBaseService,
                           ChunkEmbeddingService chunkEmbeddingService,
                           ChunkIndexService chunkIndexService,
                           @Value("${nexus.vector.enabled:false}") boolean vectorEnabled,
                           @Value("${nexus.search.enabled:false}") boolean searchEnabled) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkEmbeddingService = chunkEmbeddingService;
        this.chunkIndexService = chunkIndexService;
        this.vectorEnabled = vectorEnabled;
        this.searchEnabled = searchEnabled;
    }

    /**
     * 跑一遍知识工作流（对指定的知识库）。
     *
     * @return 每步的 状态/耗时/消息
     */
    public List<StepResult> run(Long userId, Long knowledgeBaseId) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);

        List<Step> steps = List.of(
                new Step() {
                    public String name() { return "统计文档"; }

                    public WorkflowEngine.Result execute() {
                        long count = documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                                .eq(DocumentPO::getKbId, knowledgeBaseId));
                        return WorkflowEngine.Result.ok(count + " 篇文档");
                    }
                },
                new Step() {
                    public String name() { return "统计切片"; }

                    public WorkflowEngine.Result execute() {
                        long count = chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                                .eq(ChunkPO::getKbId, knowledgeBaseId));
                        return WorkflowEngine.Result.ok(count + " 个切片");
                    }
                },
                new Step() {
                    public String name() { return "向量化"; }

                    public WorkflowEngine.Result execute() throws Exception {
                        // 能力未启用 -> 如实降级（成功但说明），不中止工作流；
                        // 能力启用后的异常 -> 失败并中止后续。不靠猜异常文本区分，用配置开关。
                        if (!vectorEnabled) {
                            return WorkflowEngine.Result.ok("向量库未启用，跳过嵌入（降级：关键词检索兜底）");
                        }
                        var result = chunkEmbeddingService.embedPendingChunks(userId, knowledgeBaseId, 100);
                        return WorkflowEngine.Result.ok("嵌入完成：" + result);
                    }
                },
                new Step() {
                    public String name() { return "建索引"; }

                    public WorkflowEngine.Result execute() throws Exception {
                        if (!searchEnabled) {
                            return WorkflowEngine.Result.ok("检索索引未启用，跳过建索（降级：SQL 关键词匹配）");
                        }
                        var result = chunkIndexService.indexPendingChunks(userId, knowledgeBaseId, 100);
                        return WorkflowEngine.Result.ok("建索引完成：" + result);
                    }
                });

        List<StepResult> results = WorkflowEngine.run(steps);
        log.info("知识工作流完成 kbId={}, 步骤数={}", knowledgeBaseId, results.size());
        return results;
    }
}