package com.lky.nexus.app.workflow;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.workflow.model.dto.WorkflowApprovalRequest;
import com.lky.nexus.app.workflow.model.dto.WorkflowNodeRunResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowRunResponse;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工作流运行查询与取消接口。 */
@RestController
@RequestMapping("/api/workflow-runs")
public class WorkflowRunController {

    private final WorkflowRuntimeService runtimeService;

    public WorkflowRunController(WorkflowRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /** 某工作流的运行历史（倒序分页）。失败运行靠它找回 runId，再去读节点轨迹。 */
    @GetMapping
    public Result<PageResult<WorkflowRunResponse>> list(
            @RequestParam Long workflowId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(runtimeService.list(CurrentUser.requireId(), workflowId, pageNum, pageSize));
    }

    @GetMapping("/{runId}")
    public Result<WorkflowRunResponse> detail(@PathVariable Long runId) {
        return Result.ok(runtimeService.get(CurrentUser.requireId(), runId));
    }

    @GetMapping("/{runId}/nodes")
    public Result<List<WorkflowNodeRunResponse>> nodes(@PathVariable Long runId) {
        return Result.ok(runtimeService.nodes(CurrentUser.requireId(), runId));
    }

    @PostMapping("/{runId}/cancel")
    public Result<WorkflowRunResponse> cancel(@PathVariable Long runId) {
        return Result.ok(runtimeService.cancel(CurrentUser.requireId(), runId));
    }

    /** 推进停在人工审批上的运行（decision = APPROVE / REJECT）。 */
    @PostMapping("/{runId}/approval")
    public Result<WorkflowRunResponse> approval(@PathVariable Long runId,
                                                @Valid @RequestBody WorkflowApprovalRequest request) {
        return Result.ok(runtimeService.approve(CurrentUser.requireId(), runId, request));
    }

    /** 从失败节点续跑：以同一版本新建一次运行，只重跑未成功的节点。 */
    @PostMapping("/{runId}/retry")
    public Result<WorkflowRunResponse> retry(@PathVariable Long runId) {
        return Result.ok(runtimeService.retry(CurrentUser.requireId(), runId));
    }
}
