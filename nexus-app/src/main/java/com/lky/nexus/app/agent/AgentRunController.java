package com.lky.nexus.app.agent;

import com.lky.nexus.app.agent.model.ApprovalScope;
import com.lky.nexus.app.agent.model.ApprovalStatus;
import com.lky.nexus.app.agent.model.dto.AgentRunResponse;
import com.lky.nexus.app.agent.model.dto.AgentRunStepResponse;
import com.lky.nexus.app.agent.model.dto.ApprovalDecisionRequest;
import com.lky.nexus.app.agent.model.dto.StartAgentRunRequest;
import com.lky.nexus.app.agent.model.dto.StartAgentRunResponse;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.common.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 运行接口（P0 §五 / §六）。
 *
 * <p>Controller 只做三件事：取 JWT 里的用户、校验请求体、把 id 交给 Service。
 * 状态机、审批原子更新、授权写入全在 Service/Mapper 层——这里写不了"先查再改"，
 * 也就写不出并发窗口。
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;

    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    /** 发起一次运行，返回 runId（String）。 */
    @PostMapping
    public Result<StartAgentRunResponse> start(@Valid @RequestBody StartAgentRunRequest request) {
        Long runId = agentRunService.start(CurrentUser.requireId(), request);
        return Result.ok(new StartAgentRunResponse(String.valueOf(runId)));
    }

    /** 运行详情（含当前待审批，刷新后可恢复现场）。 */
    @GetMapping("/{runId}")
    public Result<AgentRunResponse> get(@PathVariable String runId) {
        return Result.ok(agentRunService.get(CurrentUser.requireId(), parseRunId(runId)));
    }

    /** 运行轨迹（按执行顺序）。 */
    @GetMapping("/{runId}/steps")
    public Result<List<AgentRunStepResponse>> steps(@PathVariable String runId) {
        return Result.ok(agentRunService.steps(CurrentUser.requireId(), parseRunId(runId)));
    }

    /**
     * 提交审批决策。
     *
     * <p>返回决策后的运行详情而不是一个布尔值：调用方真正需要知道的是"现在运行是什么状态"，
     * 而这只有回读运行才能确定（批准后可能立刻又有下一个审批）。
     */
    @PostMapping("/{runId}/approval")
    public Result<AgentRunResponse> approve(@PathVariable String runId,
                                            @Valid @RequestBody ApprovalDecisionRequest request) {
        Long userId = CurrentUser.requireId();
        Long id = parseRunId(runId);
        ApprovalScope scope = parseScope(request.scopeOrDefault());
        ApprovalStatus ignored = agentRunService.decide(userId, id, request.approved(), scope, request.reason());
        return Result.ok(agentRunService.get(userId, id));
    }

    /** 取消运行（幂等；等待审批时同样可以取消）。 */
    @PostMapping("/{runId}/cancel")
    public Result<CancelRunResponse> cancel(@PathVariable String runId) {
        Long userId = CurrentUser.requireId();
        Long id = parseRunId(runId);
        boolean cancelled = agentRunService.cancel(userId, id);
        return Result.ok(new CancelRunResponse(String.valueOf(id), cancelled));
    }

    /** 取消结果：{@code cancelled=false} 表示运行已结束、本来就不需要取消（不是错误）。 */
    public record CancelRunResponse(String runId, boolean cancelled) {
    }

    private Long parseRunId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            // 非法 id 与不存在等价处理：都返回"运行不存在"，不泄露 id 形态信息
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        }
    }

    private ApprovalScope parseScope(String raw) {
        try {
            return ApprovalScope.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, ex.getMessage());
        }
    }
}
