package com.lky.nexus.app.agent.approval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.agent.mapper.AgentRunMapper;
import com.lky.nexus.app.agent.mapper.ToolApprovalMapper;
import com.lky.nexus.app.agent.mapper.ToolInvocationMapper;
import com.lky.nexus.app.agent.model.AgentRunStatus;
import com.lky.nexus.app.agent.model.ApprovalStatus;
import com.lky.nexus.app.agent.model.ToolInvocationStatus;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import com.lky.nexus.app.agent.model.entity.ToolInvocationPO;
import com.lky.nexus.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动清理：把上一个进程遗留的「在途」Agent 运行收口成明确的失败。
 *
 * <p><b>为什么需要它</b>：审批等待是「工具线程阻塞在内存里的 {@link java.util.concurrent.CompletableFuture}
 * 上，由 {@code ToolApprovalService} 的 5 分钟超时兜底」。进程重启后等待者随线程一起消失——
 * 既没有人唤醒，也没有人再触发那个超时，于是运行会<b>永远</b>停在 {@code WAITING_APPROVAL}
 * （{@code RUNNING} 同理），待决策审批行也永远是 {@code PENDING}。
 * {@code ToolApprovalService#decide} 只在「有人来决策」时才会如实标失败；用户关掉页面就没人来了。
 *
 * <p><b>这不是"恢复"，是"别撒谎"</b>：真正的恢复要在重启后继续执行那个待审批的工具调用，
 * 需要把工具的<b>原始参数</b>与模型轮次上下文落库——而 {@code ai_tool_invocation} 只存
 * {@code arguments_digest}（有意不存原文）。所以这里明确标失败，并留下可读的原因。
 *
 * <p><b>部署假设：单实例</b>。多实例共享同一库时，别的实例上合法运行中的行也会被这里改写；
 * 那种部署下应把 {@code nexus.agent.sweep-stale-runs-on-startup} 设为 {@code false}，
 * 由实例租约一类的机制裁定归属（本项目当前没有，也不需要）。
 *
 * <p>用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：后者可能在
 * 数据源/迁移就绪之前跑（与 {@code ToolRegistrationService} 的取舍相同）。
 */
@Component
public class AgentRunStaleSweeper {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStaleSweeper.class);

    /** 与 {@code ToolApprovalService#decide} 里「上下文已丢失」用同一个错误码：客户端不必区分两者。 */
    static final String INTERRUPTED_MESSAGE = "服务重启，运行已中断（在途上下文只存在于内存）";

    private final AgentRunMapper runMapper;
    private final ToolApprovalMapper approvalMapper;
    private final ToolInvocationMapper invocationMapper;
    private final ToolInvocationAuditService auditService;
    private final boolean enabled;

    public AgentRunStaleSweeper(AgentRunMapper runMapper,
                                ToolApprovalMapper approvalMapper,
                                ToolInvocationMapper invocationMapper,
                                ToolInvocationAuditService auditService,
                                @Value("${nexus.agent.sweep-stale-runs-on-startup:true}") boolean enabled) {
        this.runMapper = runMapper;
        this.approvalMapper = approvalMapper;
        this.invocationMapper = invocationMapper;
        this.auditService = auditService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        sweep();
    }

    /**
     * 执行一次收口。
     *
     * @return 本次被改写的运行数（{@code 0} = 没有遗留，正常启动路径）
     */
    public int sweep() {
        if (!enabled) {
            log.debug("启动清理已关闭（nexus.agent.sweep-stale-runs-on-startup=false），跳过在途运行收口");
            return 0;
        }
        List<AgentRunPO> stale = runMapper.selectList(Wrappers.<AgentRunPO>lambdaQuery()
                .select(AgentRunPO::getId, AgentRunPO::getStatus)
                .in(AgentRunPO::getStatus,
                        AgentRunStatus.RUNNING.name(), AgentRunStatus.WAITING_APPROVAL.name()));
        if (stale.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int closed = 0;
        for (AgentRunPO run : stale) {
            if (close(run, now)) {
                closed++;
            }
        }
        if (closed > 0) {
            log.warn("启动清理：{} 个上次进程遗留的运行已标记为 FAILED（RUNNING / WAITING_APPROVAL 没有进程接管）", closed);
        } else {
            log.debug("启动清理：查到 {} 个在途运行，但都被并发收口了，本次无需处理", stale.size());
        }
        return closed;
    }

    /** 收口单条运行；返回 {@code false} 表示它已被并发改写（不是我的事）。 */
    private boolean close(AgentRunPO run, LocalDateTime now) {
        // 条件更新：只有"仍停在读到的那个状态"的运行才被改写，避免把并发完成/取消的运行改回失败
        int updated = runMapper.compareAndSetStatus(run.getId(), run.getStatus(),
                AgentRunStatus.FAILED.name(), now);
        if (updated == 0) {
            return false;
        }
        runMapper.update(null, Wrappers.<AgentRunPO>lambdaUpdate()
                .eq(AgentRunPO::getId, run.getId())
                .set(AgentRunPO::getErrorCode, ErrorCode.AGENT_RUN_STATE_INVALID.getCode())
                .set(AgentRunPO::getErrorMsg, INTERRUPTED_MESSAGE)
                .set(AgentRunPO::getFinishedAt, now));

        int approvals = approvalMapper.cancelPending(run.getId(), ApprovalStatus.EXPIRED.name(), now);
        int invocations = closePendingInvocations(run.getId());
        log.warn("运行收口：runId={} 原状态={} → FAILED，作废待决策审批 {} 条、待审批审计行 {} 条",
                run.getId(), run.getStatus(), approvals, invocations);
        return true;
    }

    /**
     * 待审批的审计行置为 {@code CANCELLED}（不是 {@code FAILED}）：工具根本没执行过，
     * 与"执行了但失败"是两回事，审计里不该混为一谈。
     */
    private int closePendingInvocations(Long runId) {
        List<ToolInvocationPO> pending = invocationMapper.selectList(Wrappers.<ToolInvocationPO>lambdaQuery()
                .eq(ToolInvocationPO::getRunId, runId)
                .eq(ToolInvocationPO::getStatus, ToolInvocationStatus.PENDING_APPROVAL.name()));
        for (ToolInvocationPO row : pending) {
            auditService.finish(row.getId(), ToolInvocationStatus.CANCELLED, null,
                    ErrorCode.AGENT_RUN_STATE_INVALID.getCode(), null);
        }
        return pending.size();
    }
}
