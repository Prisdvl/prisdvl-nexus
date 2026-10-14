package com.lky.nexus.app.workflow;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识工作流接口（Phase 6.2）。
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowService workflowService;

    /** 注入 {@link WorkflowService}，由 Spring 完成。 */
    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * 跑一遍指定知识库的知识工作流，返回每步的 状态/耗时/消息。
     *
     * @param knowledgeBaseId 知识库 ID（会校验当前用户的所有权）
     * @return 每步的执行结果列表（顺序与步骤定义一致）
     */
    @GetMapping("/run/{knowledgeBaseId}")
    public Result<List<WorkflowEngine.StepResult>> run(@PathVariable Long knowledgeBaseId) {
        return Result.ok(workflowService.run(CurrentUser.requireId(), knowledgeBaseId));
    }
}