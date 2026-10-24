package com.lky.nexus.app.automation;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.automation.model.dto.AutomationResponse;
import com.lky.nexus.app.automation.model.dto.AutomationRunResponse;
import com.lky.nexus.app.automation.model.dto.CreateAutomationRequest;
import com.lky.nexus.app.automation.model.dto.UpdateAutomationRequest;
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

/**
 * 自动化任务的 HTTP 入口。
 *
 * <p>这里只做三件事：取 userId、接参数、包 {@link Result}。
 * 归属校验、排期计算、执行与重试全在 {@link AutomationService} / {@link AutomationRunner}。
 *
 * <p>路径顺序上 {@code /{automationId}/runs} 与 {@code /{automationId}/run} 都带字面量尾段，
 * 不会和 {@code /{automationId}} 打架。
 */
@RestController
@RequestMapping("/api/automations")
public class AutomationController {

    private final AutomationService automationService;

    public AutomationController(AutomationService automationService) {
        this.automationService = automationService;
    }

    /**
     * 分页列出当前用户的自动化任务。
     *
     * @param pageNum  页码，默认 1
     * @param pageSize 每页条数，默认 20
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResult<AutomationResponse>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(automationService.list(CurrentUser.requireId(), pageNum, pageSize));
    }

    /**
     * 新建任务。
     *
     * @param request 创建参数
     * @return 新任务的视图（含算好的 nextRunAt）
     */
    @PostMapping
    public Result<AutomationResponse> create(@Valid @RequestBody CreateAutomationRequest request) {
        return Result.ok(automationService.create(CurrentUser.requireId(), request));
    }

    /**
     * 局部更新：请求体里没出现的字段保持不变。
     *
     * @param automationId 任务 ID
     * @param request      更新参数
     * @return 更新后的视图
     */
    @PutMapping("/{automationId}")
    public Result<AutomationResponse> update(@PathVariable Long automationId,
                                             @Valid @RequestBody UpdateAutomationRequest request) {
        return Result.ok(automationService.update(CurrentUser.requireId(), automationId, request));
    }

    /**
     * 删除任务（逻辑删除；运行历史保留）。
     *
     * @param automationId 任务 ID
     * @return 空
     */
    @DeleteMapping("/{automationId}")
    public Result<Void> delete(@PathVariable Long automationId) {
        automationService.delete(CurrentUser.requireId(), automationId);
        return Result.ok();
    }

    /**
     * 立即跑一次。
     *
     * <p>同步执行：返回的就是这次运行的结果。跑挂了也返回 200，
     * 失败原因在 {@code status=FAILED} + {@code errorCode/errorMessage} 里——
     * "任务执行失败"不是一个 HTTP 错误，它是这个接口的正常业务结果。
     *
     * @param automationId 任务 ID
     * @return 这次运行的记录
     */
    @PostMapping("/{automationId}/run")
    public Result<AutomationRunResponse> run(@PathVariable Long automationId) {
        return Result.ok(automationService.runNow(CurrentUser.requireId(), automationId));
    }

    /**
     * 最近 N 条运行记录（倒序）。
     *
     * @param automationId 任务 ID
     * @param limit        条数，默认 20，上限 100
     * @return 运行记录列表
     */
    @GetMapping("/{automationId}/runs")
    public Result<List<AutomationRunResponse>> runs(@PathVariable Long automationId,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(automationService.recentRuns(CurrentUser.requireId(), automationId, limit));
    }
}
