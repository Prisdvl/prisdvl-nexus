package com.lky.nexus.app.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
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
    private final boolean vectorEnabled;
    private final boolean searchEnabled;

    public WorkflowService(KnowledgeBaseMapper knowledgeBaseMapper,
                           DocumentMapper documentMapper,
                           ChunkMapper chunkMapper,
                           KnowledgeBaseService knowledgeBaseService,
                           @Value("${nexus.vector.enabled:false}") boolean vectorEnabled,
                           @Value("${nexus.search.enabled:false}") boolean searchEnabled) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseService = knowledgeBaseService;
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
                    public String name() { return "检查向量库"; }

                    public WorkflowEngine.Result execute() {
                        return vectorEnabled
                                ? WorkflowEngine.Result.ok("pgvector 可用，下一步可执行嵌入")
                                : WorkflowEngine.Result.ok("向量库未启用（降级：跳过嵌入，改用关键词检索）");
                    }
                },
                new Step() {
                    public String name() { return "检查检索索引"; }

                    public WorkflowEngine.Result execute() {
                        return searchEnabled
                                ? WorkflowEngine.Result.ok("Elasticsearch 可用，下一步可执行建索引")
                                : WorkflowEngine.Result.ok("检索索引未启用（降级：SQL 关键词匹配）");
                    }
                });

        List<StepResult> results = WorkflowEngine.run(steps);
        log.info("知识工作流完成 kbId={}, 步骤数={}", knowledgeBaseId, results.size());
        return results;
    }
}