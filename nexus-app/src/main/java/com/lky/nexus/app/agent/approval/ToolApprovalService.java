package com.lky.nexus.app.agent.approval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.mapper.AgentRunMapper;
import com.lky.nexus.app.agent.mapper.ToolApprovalMapper;
import com.lky.nexus.app.agent.model.AgentRunStatus;
import com.lky.nexus.app.agent.model.ApprovalScope;
import com.lky.nexus.app.agent.model.ApprovalStatus;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import com.lky.nexus.app.agent.model.entity.ToolApprovalPO;
import com.lky.nexus.app.agent.tool.ToolGrantService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 人工审批闭环（P0 §六）。
 *
 * <p>等待发生在工具线程里：模型循环里唯一需要暂停的点就是"这一次工具调用还没被批准"，
 * 而工具回调运行在 boundedElastic（流式）或运行专用线程池（POST /api/agent/runs）上，
 * 不是 HTTP 请求线程。批准后**同一次调用**继续执行，模型上下文与历史原样保留——
 * 这就是"恢复原来的运行上下文，而不是启动一个丢失历史状态的新 Agent"。
 *
 * <p>等待有界（默认 5 分钟，nexus.agent.approval-timeout）：无限期挂起会让一次运行
 * 永远停在 WAITING_APPROVAL，没有任何东西会把它收掉。
 *
 * <p>决策用 UPDATE ... WHERE status='PENDING' 的原子更新落定，因此重复提交、并发提交、
 * 审批与取消竞争时只有一次能成功。
 */
@Service
public class ToolApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalService.class);

    private final AgentRunMapper runMapper;
    private final ToolApprovalMapper approvalMapper;
    private final ToolInvocationAuditService auditService;
    private final ToolGrantService grantService;
    private final Duration approvalTimeout;

    /** approvalId → 等待中的调用。仅在内存里：进程重启后没有可恢复的运行上下文。 */
    private final Map<Long, PendingApproval> pendingById = new ConcurrentHashMap<>();

    private record PendingApproval(Long runId, CompletableFuture<Outcome> future) {
    }

    /**
     * 一次工具调用的最终结论。
     *
     * @param execute     是否允许真正执行工具
     * @param status      审批状态（APPROVED / DENIED / CANCELLED / EXPIRED）
     * @param observation 回灌给模型的观察文本（拒绝与取消都走它，不抛异常）
     */
    public record Outcome(boolean execute, ApprovalStatus status, String observation) {
    }

    /** 一次挂起的审批：approvalId 会随 SSE 事件发给客户端。 */
    public record Ticket(Long runId, Long approvalId, Long invocationId,
                         CompletableFuture<Outcome> future) {
    }

    public ToolApprovalService(AgentRunMapper runMapper,
                               ToolApprovalMapper approvalMapper,
                               ToolInvocationAuditService auditService,
                               ToolGrantService grantService,
                               @Value("${nexus.agent.approval-timeout:PT5M}") Duration approvalTimeout) {
        this.runMapper = runMapper;
        this.approvalMapper = approvalMapper;
        this.auditService = auditService;
        this.grantService = grantService;
        this.approvalTimeout = approvalTimeout;
    }

    /**
     * 请求审批：先落库（运行状态 / 审批 / 审计），再发事件，最后等待。
     *
     * <p>顺序是硬约束：事件先发、状态后落库的话，用户批准时可能查不到待决策记录，
     * 或者客户端刷新后看到的仍是 RUNNING。
     */
    @Transactional
    public Ticket request(Long userId, Long runId, String toolCode, ToolPermission permission,
                          String argumentsDigest, String reason, AgentContext.ToolCallListener listener) {
        Long effectiveRunId = runId != null ? runId : createImplicitRun(userId);
        markWaitingApproval(effectiveRunId);

        Long invocationId = auditService.begin(userId, effectiveRunId, toolCode, permission, argumentsDigest);
        ToolApprovalPO approval = new ToolApprovalPO();
        approval.setRunId(effectiveRunId);
        approval.setUserId(userId);
        approval.setInvocationId(invocationId);
        approval.setToolCode(toolCode);
        approval.setArgumentsDigest(argumentsDigest);
        approval.setReason(reason);
        approval.setStatus(ApprovalStatus.PENDING.name());
        approval.setCreatedAt(LocalDateTime.now());
        approvalMapper.insert(approval);
        auditService.attachApproval(invocationId, approval.getId());

        CompletableFuture<Outcome> future = new CompletableFuture<>();
        pendingById.put(approval.getId(), new PendingApproval(effectiveRunId, future));

        AgentContext.ApprovalRequired event = new AgentContext.ApprovalRequired(
                effectiveRunId, approval.getId(), toolCode, argumentsDigest, reason);
        if (listener != null) {
            try {
                listener.onApprovalRequired(event);
            } catch (Exception ex) {
                // 推送失败不能把审批本身弄丢：WAITING_APPROVAL 已落库，用户可查询发现
                log.warn("审批事件推送失败（状态已落库，可通过运行查询恢复）：runId={} approvalId={} {}",
                        effectiveRunId, approval.getId(), ex.getMessage());
            }
        }
        log.info("工具调用进入审批等待：runId={} approvalId={} tool={} permission={}",
                effectiveRunId, approval.getId(), toolCode, permission);
        return new Ticket(effectiveRunId, approval.getId(), invocationId, future);
    }

    /** 有界等待审批结果；超时按 EXPIRED 处理并让运行失败（绝不默认放行）。 */
    public Outcome await(Ticket ticket) {
        try {
            return ticket.future().get(approvalTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.warn("等待审批超时：runId={} approvalId={}（按未执行处理）", ticket.runId(), ticket.approvalId());
            return expire(ticket);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Outcome(false, ApprovalStatus.CANCELLED, cancelledObservation());
        } catch (ExecutionException ex) {
            log.error("等待审批时出现异常 approvalId={}", ticket.approvalId(), ex.getCause());
            return new Outcome(false, ApprovalStatus.EXPIRED,
                    "审批流程出现异常，工具未执行。请告知用户这一步没有做成。");
        } finally {
            pendingById.remove(ticket.approvalId());
        }
    }

    /** 提交审批决策（POST /api/agent/runs/{runId}/approval）。 */
    @Transactional
    public ApprovalStatus decide(Long userId, Long runId, boolean approved, ApprovalScope scope, String reason) {
        AgentRunPO run = requireOwned(userId, runId);
        if (!AgentRunStatus.WAITING_APPROVAL.name().equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_STATE_INVALID,
                    "该运行当前状态为 %s，没有等待决策的审批".formatted(run.getStatus()));
        }
        ToolApprovalPO approval = latestPending(runId);
        if (approval == null) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_PENDING);
        }

        // 拒绝不区分 scope：approved=false 一律按拒绝处理，且绝不写授权
        ApprovalScope effectiveScope = approved ? scope : ApprovalScope.DENY;
        if (approved && scope == ApprovalScope.DENY) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "approved=true 与 scope=deny 是矛盾的组合");
        }

        ApprovalStatus target = approved ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
        int updated = approvalMapper.decide(approval.getId(), target.name(), effectiveScope.name(),
                reason, LocalDateTime.now());
        if (updated == 0) {
            // 另一个并发决策已经生效：这不是错误，是"来晚了"
            throw new BusinessException(ErrorCode.APPROVAL_NOT_PENDING);
        }

        if (approved && effectiveScope == ApprovalScope.ALWAYS) {
            grantService.grantAlways(userId, approval.getToolCode());
        }

        Outcome outcome = approved
                ? new Outcome(true, ApprovalStatus.APPROVED, null)
                : new Outcome(false, ApprovalStatus.DENIED, deniedObservation(approval.getToolCode(), reason));

        // 只有真正拿到 WAITING_APPROVAL → RUNNING 的那一次转换才唤醒工具线程。
        // 若同时有人在取消（状态已变 CANCELLED），这里 CAS 失败 ⇒ 绝不执行工具——
        // 这就是"并发审批与取消竞争时只有一个合法转换成功"的实现。
        boolean resumed = runMapper.compareAndSetStatus(runId, AgentRunStatus.WAITING_APPROVAL.name(),
                AgentRunStatus.RUNNING.name(), LocalDateTime.now()) > 0;
        PendingApproval pending = pendingById.remove(approval.getId());
        if (!resumed) {
            if (pending != null) {
                pending.future().complete(new Outcome(false, ApprovalStatus.CANCELLED, cancelledObservation()));
            }
            throw new BusinessException(ErrorCode.AGENT_RUN_STATE_INVALID,
                    "运行已不在等待审批状态（可能已被取消），本次决策未生效");
        }
        if (pending != null) {
            pending.future().complete(outcome);
        } else {
            // 内存里没有等待者：多半是进程重启导致运行上下文丢失。
            // 决策已落库，但工具不会执行——如实标记失败，不假装成功
            log.warn("审批已落定但内存中没有等待者（服务重启？）：runId={} approvalId={}", runId, approval.getId());
            markFailed(runId, ErrorCode.AGENT_RUN_STATE_INVALID.getCode(),
                    "运行上下文已丢失（服务重启），审批结果无法恢复执行");
        }
        return target;
    }

    /**
     * 取消运行：幂等，允许在等待审批时取消。
     *
     * @return true = 运行处于（或已进入）CANCELLED
     */
    @Transactional
    public boolean cancelRun(Long userId, Long runId) {
        AgentRunPO run = requireOwned(userId, runId);
        if (AgentRunStatus.CANCELLED.name().equals(run.getStatus())) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean changed = runMapper.compareAndSetStatus(runId, AgentRunStatus.RUNNING.name(),
                AgentRunStatus.CANCELLED.name(), now) > 0
                || runMapper.compareAndSetStatus(runId, AgentRunStatus.WAITING_APPROVAL.name(),
                AgentRunStatus.CANCELLED.name(), now) > 0;
        if (!changed) {
            // 已经结束的运行（DONE/FAILED/MAX_ITERATIONS）不算取消成功，也不报错
            AgentRunPO current = runMapper.selectById(runId);
            return current != null && AgentRunStatus.CANCELLED.name().equals(current.getStatus());
        }
        int cancelled = approvalMapper.cancelPending(runId, ApprovalStatus.CANCELLED.name(), now);
        pendingById.entrySet().removeIf(entry -> {
            if (!entry.getValue().runId().equals(runId)) {
                return false;
            }
            entry.getValue().future().complete(new Outcome(false, ApprovalStatus.CANCELLED,
                    cancelledObservation()));
            return true;
        });
        log.info("运行已取消：runId={}，作废待决策审批 {} 条", runId, cancelled);
        return true;
    }

    /** 运行是否已被取消（执行壳在启动新工具调用前用它短路）。 */
    public boolean isCancelled(Long runId) {
        if (runId == null) {
            return false;
        }
        AgentRunPO run = runMapper.selectById(runId);
        return run != null && AgentRunStatus.CANCELLED.name().equals(run.getStatus());
    }

    /**
     * 当前运行是否已不允许再启动工具调用。
     *
     * @return null = 可以继续；非 null = 拒绝原因（作为 observation 回灌，不抛异常）
     */
    public String executionBlockedReason(Long runId) {
        if (runId == null) {
            return null;
        }
        AgentRunPO run = runMapper.selectById(runId);
        if (run == null) {
            return null;
        }
        AgentRunStatus status = AgentRunStatus.parse(run.getStatus());
        return switch (status) {
            case RUNNING, WAITING_APPROVAL -> null;
            case CANCELLED -> "运行已取消，不再执行任何工具调用。";
            case MAX_ITERATIONS -> "运行已达到最大迭代次数，不再执行任何工具调用。";
            case FAILED -> "运行已失败，不再执行任何工具调用。";
            case DONE -> "运行已结束，不再执行任何工具调用。";
        };
    }

    /** 待决策审批（运行详情接口回显"当前在等哪一个审批"）。 */
    public ToolApprovalPO latestPending(Long runId) {
        List<ToolApprovalPO> rows = approvalMapper.selectList(Wrappers.<ToolApprovalPO>lambdaQuery()
                .eq(ToolApprovalPO::getRunId, runId)
                .eq(ToolApprovalPO::getStatus, ApprovalStatus.PENDING.name())
                .orderByDesc(ToolApprovalPO::getCreatedAt)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 运行归属校验：不存在与越权统一 5012/404。 */
    public AgentRunPO requireOwned(Long userId, Long runId) {
        AgentRunPO run = runMapper.selectById(runId);
        if (run == null || !run.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        }
        return run;
    }

    // ------------------------------------------------------------------

    private Long createImplicitRun(Long userId) {
        AgentRunPO run = new AgentRunPO();
        run.setUserId(userId);
        run.setStatus(AgentRunStatus.RUNNING.name());
        run.setMaxIterations(8);
        run.setIterationCount(0);
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run.getId();
    }

    private void markWaitingApproval(Long runId) {
        LocalDateTime now = LocalDateTime.now();
        if (runMapper.compareAndSetStatus(runId, AgentRunStatus.RUNNING.name(),
                AgentRunStatus.WAITING_APPROVAL.name(), now) > 0) {
            return;
        }
        AgentRunPO current = runMapper.selectById(runId);
        if (current == null || !AgentRunStatus.WAITING_APPROVAL.name().equals(current.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_RUN_STATE_INVALID,
                    "运行当前状态为 %s，无法进入审批等待"
                            .formatted(current == null ? "未知" : current.getStatus()));
        }
    }

    private Outcome expire(Ticket ticket) {
        LocalDateTime now = LocalDateTime.now();
        approvalMapper.decide(ticket.approvalId(), ApprovalStatus.EXPIRED.name(), null,
                "等待超时，未执行", now);
        markFailed(ticket.runId(), ErrorCode.APPROVAL_NOT_PENDING.getCode(),
                "工具审批超时（" + approvalTimeout.toMinutes() + " 分钟未决策），已按未执行处理");
        return new Outcome(false, ApprovalStatus.EXPIRED,
                "等待人工确认超时，工具未执行。请换一种方式完成这一步，或如实告知用户这一步没有做成。");
    }

    private void markFailed(Long runId, Integer errorCode, String message) {
        AgentRunPO run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        run.setStatus(AgentRunStatus.FAILED.name());
        run.setErrorCode(errorCode);
        run.setErrorMsg(message);
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private String deniedObservation(String toolCode, String reason) {
        String detail = (reason == null || reason.isBlank()) ? "未提供原因" : reason;
        return "用户拒绝执行工具 %s。原因：%s。请根据当前上下文选择其他可行方案。"
                .formatted(toolCode, detail);
    }

    private String cancelledObservation() {
        return "运行已被用户取消，工具未执行。";
    }
}
