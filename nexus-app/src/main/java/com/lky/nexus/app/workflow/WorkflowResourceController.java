package com.lky.nexus.app.workflow;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.workflow.model.dto.CreateWorkflowRequest;
import com.lky.nexus.app.workflow.model.dto.RunWorkflowRequest;
import com.lky.nexus.app.workflow.model.dto.SaveWorkflowDraftRequest;
import com.lky.nexus.app.workflow.model.dto.UpdateWorkflowRequest;
import com.lky.nexus.app.workflow.model.dto.WorkflowNodeRunResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowRunResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowValidationResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowVersionResponse;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
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

import java.util.List;

/** 可编排工作流资源接口。旧的 /api/workflow/run/** 知识工作流保持不变。 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowResourceController {

    private final WorkflowDefinitionService definitionService;
    private final WorkflowRuntimeService runtimeService;

    public WorkflowResourceController(WorkflowDefinitionService definitionService,
                                      WorkflowRuntimeService runtimeService) {
        this.definitionService = definitionService;
        this.runtimeService = runtimeService;
    }

    @PostMapping
    public Result<WorkflowResponse> create(@Valid @RequestBody CreateWorkflowRequest request) {
        return Result.ok(definitionService.create(CurrentUser.requireId(), request));
    }

    @GetMapping
    public Result<PageResult<WorkflowResponse>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(definitionService.list(CurrentUser.requireId(), pageNum, pageSize));
    }

    @GetMapping("/{workflowId}")
    public Result<WorkflowResponse> detail(@PathVariable Long workflowId) {
        return Result.ok(definitionService.detail(CurrentUser.requireId(), workflowId));
    }

    /** 改元信息（名称 / 描述）；局部更新，不产生新版本。 */
    @PutMapping("/{workflowId}")
    public Result<WorkflowResponse> update(@PathVariable Long workflowId,
                                           @Valid @RequestBody UpdateWorkflowRequest request) {
        return Result.ok(definitionService.update(CurrentUser.requireId(), workflowId, request));
    }

    @PutMapping("/{workflowId}/draft")
    public Result<WorkflowResponse> saveDraft(@PathVariable Long workflowId,
                                              @Valid @RequestBody SaveWorkflowDraftRequest request) {
        return Result.ok(definitionService.saveDraft(CurrentUser.requireId(), workflowId, request));
    }

    @DeleteMapping("/{workflowId}")
    public Result<Void> delete(@PathVariable Long workflowId) {
        definitionService.delete(CurrentUser.requireId(), workflowId);
        return Result.ok();
    }

    @PostMapping("/{workflowId}/validate")
    public Result<WorkflowValidationResponse> validate(@PathVariable Long workflowId) {
        return Result.ok(definitionService.validateDraft(CurrentUser.requireId(), workflowId));
    }

    @PostMapping("/{workflowId}/publish")
    public Result<WorkflowResponse> publish(@PathVariable Long workflowId) {
        return Result.ok(definitionService.publish(CurrentUser.requireId(), workflowId));
    }

    @GetMapping("/{workflowId}/versions")
    public Result<List<WorkflowVersionResponse>> versions(@PathVariable Long workflowId) {
        return Result.ok(definitionService.versions(CurrentUser.requireId(), workflowId));
    }

    @PostMapping("/{workflowId}/debug")
    public Result<WorkflowRunResponse> debug(@PathVariable Long workflowId,
                                             @RequestBody(required = false) RunWorkflowRequest request) {
        return Result.ok(runtimeService.run(CurrentUser.requireId(), workflowId, safe(request), "DEBUG"));
    }

    @PostMapping("/{workflowId}/runs")
    public Result<WorkflowRunResponse> run(@PathVariable Long workflowId,
                                           @RequestBody(required = false) RunWorkflowRequest request) {
        return Result.ok(runtimeService.run(CurrentUser.requireId(), workflowId, safe(request), "MANUAL"));
    }

    @GetMapping("/runs/{runId}")
    public Result<WorkflowRunResponse> runDetail(@PathVariable Long runId) {
        return Result.ok(runtimeService.get(CurrentUser.requireId(), runId));
    }

    @GetMapping("/runs/{runId}/nodes")
    public Result<List<WorkflowNodeRunResponse>> runNodes(@PathVariable Long runId) {
        return Result.ok(runtimeService.nodes(CurrentUser.requireId(), runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public Result<WorkflowRunResponse> cancelRun(@PathVariable Long runId) {
        return Result.ok(runtimeService.cancel(CurrentUser.requireId(), runId));
    }

    private RunWorkflowRequest safe(RunWorkflowRequest request) {
        return request == null ? new RunWorkflowRequest(null) : request;
    }
}
