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

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** 跑一遍知识工作流，返回每步的 状态/耗时/消息。 */
    @GetMapping("/run/{knowledgeBaseId}")
    public Result<List<WorkflowEngine.StepResult>> run(@PathVariable Long knowledgeBaseId) {
        return Result.ok(workflowService.run(CurrentUser.requireId(), knowledgeBaseId));
    }
}