package com.lky.nexus.app.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.app.agent.approval.AgentTraceGuards;
import com.lky.nexus.app.agent.approval.AgentRunTraceRecorder;
import com.lky.nexus.app.agent.approval.ToolApprovalService;
import com.lky.nexus.app.agent.mapper.AgentRunMapper;
import com.lky.nexus.app.agent.mapper.AgentRunStepMapper;
import com.lky.nexus.app.agent.model.AgentRunStatus;
import com.lky.nexus.app.agent.model.AgentStepType;
import com.lky.nexus.app.agent.model.ApprovalScope;
import com.lky.nexus.app.agent.model.ApprovalStatus;
import com.lky.nexus.app.agent.model.RunStepStatus;
import com.lky.nexus.app.agent.model.dto.AgentRunResponse;
import com.lky.nexus.app.agent.model.dto.AgentRunStepResponse;
import com.lky.nexus.app.agent.model.dto.PendingApprovalResponse;
import com.lky.nexus.app.agent.model.dto.StartAgentRunRequest;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import com.lky.nexus.app.agent.model.entity.AgentRunStepPO;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 运行状态机（P0 §五）。
 *
 * <p>它把"跑一次 Agent"从"一次 HTTP 调用里一口气跑完"变成**可查询、可审批、可取消**的运行：
 * 发起只返回 runId，真正执行在专用线程池上，状态与轨迹落库。
 * 这样等待人工审批时既不占住 HTTP 线程，客户端刷新后也能靠 runId 恢复现场。
 *
 * <p>执行线程用虚拟线程（Java 21）：一次运行只在等模型与等审批时阻塞，
 * 是典型的"大量 IO 阻塞"负载，不需要也不该去占平台线程池的固定名额。
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    /** 与 V22 的默认值一致；框架自己的轮数上限仍是最终防线。 */
    private static final int DEFAULT_MAX_ITERATIONS = 8;

    private final AgentRunMapper runMapper;
    private final AgentRunStepMapper stepMapper;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final AgentRuntime agentRuntime;
    private final ToolApprovalService approvalService;
    private final AgentRunTraceRecorder traceRecorder;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AgentRunService(AgentRunMapper runMapper,
                           AgentRunStepMapper stepMapper,
                           AgentService agentService,
                           ConversationService conversationService,
                           AgentRuntime agentRuntime,
                           ToolApprovalService approvalService,
                           AgentRunTraceRecorder traceRecorder) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.agentService = agentService;
        this.conversationService = conversationService;
        this.agentRuntime = agentRuntime;
        this.approvalService = approvalService;
        this.traceRecorder = traceRecorder;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 发起一次运行：校验归属 → 落 RUNNING → 交给执行线程 → 立刻返回 runId。
     *
     * <p>校验必须在落库与提交之前：一个不属于当前用户的会话 id 如果先进了执行队列，
     * 越权就不是"被拒绝"，而是"真的跑了"。
     */
    @Transactional
    public Long start(Long userId, StartAgentRunRequest request) {
        Long agentId = parseId(request.agentId(), "agentId");
        Long conversationId = parseId(request.conversationId(), "conversationId");
        // 归属校验：不存在与越权都是 404/业务码（不泄露资源存在性）
        AgentDefinition definition = agentId == null
                ? agentService.resolveDefault(userId)
                : agentService.requireDefinition(userId, agentId);
        if (conversationId != null) {
            conversationService.requireOwned(userId, conversationId);
        }
        AgentRunPO run = new AgentRunPO();
        run.setUserId(userId);
        run.setConversationId(conversationId);
        // 落库的是**解析后**的 agent id（会话没选 Agent 时也要记录"其实是内置默认在跑"）
        run.setAgentId(definition.agentId());
        run.setQuestion(request.question());
        run.setStatus(AgentRunStatus.RUNNING.name());
        run.setMaxIterations(DEFAULT_MAX_ITERATIONS);
        run.setIterationCount(0);
        run.setModelRole(definition.modelRole());
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        Long runId = run.getId();
        executor.submit(() -> execute(userId, runId, definition, request.question()));
        log.info("运行已发起：runId={} userId={} agent={}", runId, userId, definition.describe());
        return runId;
    }

    public AgentRunResponse get(Long userId, Long runId) {
        AgentRunPO run = approvalService.requireOwned(userId, runId);
        return AgentRunResponse.of(run, PendingApprovalResponse.of(approvalService.latestPending(runId)));
    }

    public List<AgentRunStepResponse> steps(Long userId, Long runId) {
        approvalService.requireOwned(userId, runId);
        return stepMapper.selectList(Wrappers.<AgentRunStepPO>lambdaQuery()
                        .eq(AgentRunStepPO::getRunId, runId)
                        .orderByAsc(AgentRunStepPO::getSeq))
                .stream()
                .map(AgentRunStepResponse::of)
                .toList();
    }

    public boolean cancel(Long userId, Long runId) {
        return approvalService.cancelRun(userId, runId);
    }

    /** 提交审批：状态机与授权写入都在 {@link ToolApprovalService} 里（原子条件更新）。 */
    public ApprovalStatus decide(Long userId, Long runId, boolean approved,
                                 ApprovalScope scope, String reason) {
        return approvalService.decide(userId, runId, approved, scope, reason);
    }

    // ------------------------------------------------------------------

    private void execute(Long userId, Long runId, AgentDefinition definition, String question) {
        AgentRunTraceRecorder.RunTrace trace = traceRecorder.begin(runId);
        trace.append(AgentStepType.THINK, null, null,
                "开始运行：Agent=%s，模型角色=%s，最大迭代 %d 次"
                        .formatted(definition.describe(), definition.modelRole(), DEFAULT_MAX_ITERATIONS),
                RunStepStatus.OK);
        try {
            AgentAnswer answer = agentRuntime.ask(userId, definition, question, trace);
            trace.append(AgentStepType.ANSWER, null, null,
                    AgentTraceGuards.digest(answer.answer()), RunStepStatus.OK);
            finish(runId, AgentRunStatus.DONE, answer.answer(), null, null);
        } catch (BusinessException ex) {
            if (ex.getCode() == ErrorCode.AGENT_MAX_ITERATIONS_EXCEEDED.getCode()) {
                finish(runId, AgentRunStatus.MAX_ITERATIONS, null, ex.getCode(), ex.getMessage());
                return;
            }
            if (ex.getCode() == ErrorCode.AGENT_RUN_CANCELLED.getCode()
                    || approvalService.isCancelled(runId)) {
                // 取消是用户的明确意图，不能被改写成 FAILED
                log.info("运行已取消，跳过失败落定：runId={}", runId);
                return;
            }
            log.warn("运行失败：runId={} code={} {}", runId, ex.getCode(), ex.getMessage());
            finish(runId, AgentRunStatus.FAILED, null, ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("运行出现未预期异常：runId={}", runId, ex);
            finish(runId, AgentRunStatus.FAILED, null, ErrorCode.INTERNAL_ERROR.getCode(), "运行失败");
        }
    }

    /**
     * 落定终态：用条件更新，只有还在 RUNNING/WAITING_APPROVAL 的运行才会被改写。
     *
     * <p>这一步是"取消优先"的保证：如果运行已被取消，DONE 的 CAS 会失败，
     * 终态保持 CANCELLED——不会出现"用户点了取消，界面却显示完成"。
     */
    private void finish(Long runId, AgentRunStatus target, String answer, Integer errorCode, String errorMsg) {
        AgentRunPO run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        AgentRunStatus current = AgentRunStatus.parse(run.getStatus());
        if (current.isTerminal()) {
            log.info("运行已是终态 {}，不再改写为 {}：runId={}", current, target, runId);
            return;
        }
        run.setStatus(target.name());
        run.setAnswer(answer);
        run.setErrorCode(errorCode);
        run.setErrorMsg(errorMsg);
        run.setFinishedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private Long parseId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, field + " 不是合法的资源 id");
        }
    }

}
