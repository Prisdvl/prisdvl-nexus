package com.lky.nexus.app.automation;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.app.agent.AgentAnswer;
import com.lky.nexus.app.agent.AgentRuntime;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.automation.mapper.AutomationMapper;
import com.lky.nexus.app.automation.mapper.AutomationRunMapper;
import com.lky.nexus.app.automation.model.AutomationRunStatus;
import com.lky.nexus.app.automation.model.dto.AutomationRunResponse;
import com.lky.nexus.app.automation.model.entity.AutomationPO;
import com.lky.nexus.app.automation.model.entity.AutomationRunPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行一次自动化：复用既有 Agent 链路，产出一条 {@code automation_run}。
 *
 * <p>三条设计决定，都是为了"失败可追溯"：
 * <ol>
 *   <li><b>先落 RUNNING 再执行</b>：模型调用可能挂几十秒，先落库才能看到"正在跑"，
 *       也才能让"进程崩了"留下一条 RUNNING 记录（而不是什么都没有）。</li>
 *   <li><b>失败不抛异常</b>：调用失败时把 {@code errorCode} + {@code errorMessage} 写进运行记录，
 *       然后把记录原样返回给调用方。立即跑的用户看到的是"这次运行失败 + 原因"，
 *       而不是一个 500 再自己去猜；调度器那边也不会因为一条任务失败而中断整批扫描。</li>
 *   <li><b>失败重试只作用于到点触发</b>：立即跑是用户手动点的，失败说明此刻环境有问题，
 *       替他排一次自动重试只会让人困惑；到点触发本来就没人在看，先用短退避重试一次更合理。</li>
 * </ol>
 */
@Service
public class AutomationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutomationRunner.class);

    /** 运行记录的 error_message 列宽；超长截断，不因为一句异常把整条记录写失败。 */
    private static final int ERROR_MESSAGE_MAX = 1000;

    private final AutomationMapper automationMapper;
    private final AutomationRunMapper runMapper;
    private final AgentService agentService;
    private final AgentRuntime agentRuntime;
    private final int maxRetries;
    private final long retryDelayMs;

    public AutomationRunner(AutomationMapper automationMapper,
                            AutomationRunMapper runMapper,
                            AgentService agentService,
                            AgentRuntime agentRuntime,
                            @Value("${nexus.automation.max-retries:2}") int maxRetries,
                            @Value("${nexus.automation.retry-delay-ms:60000}") long retryDelayMs) {
        this.automationMapper = automationMapper;
        this.runMapper = runMapper;
        this.agentService = agentService;
        this.agentRuntime = agentRuntime;
        this.maxRetries = Math.max(maxRetries, 0);
        this.retryDelayMs = Math.max(retryDelayMs, 0L);
    }

    /**
     * 跑一次并落库。
     *
     * @param userId      任务所有者
     * @param automation  任务定义（调用方已经校验过归属）
     * @param triggerType {@code MANUAL} 或 {@code SCHEDULED}
     * @return 这次运行的记录（失败时 status=FAILED 且带 errorCode/errorMessage）
     */
    public AutomationRunResponse run(Long userId, AutomationPO automation, String triggerType) {
        LocalDateTime startedAt = LocalDateTime.now();
        AutomationRunPO run = new AutomationRunPO();
        run.setAutomationId(automation.getId());
        run.setUserId(userId);
        run.setTriggerType(triggerType);
        run.setStatus(AutomationRunStatus.RUNNING.name());
        run.setStartedAt(startedAt);
        run.setCreatedAt(startedAt);
        run.setUpdatedAt(startedAt);
        runMapper.insert(run);

        String answerText = null;
        Integer errorCode = null;
        String errorMessage = null;
        boolean succeeded = false;
        AutomationUsageBridge.Usage usage;
        AutomationUsageBridge.begin();
        try {
            AgentDefinition definition = resolveDefinition(userId, automation);
            AgentAnswer answer = agentRuntime.ask(userId, definition, automation.getPrompt());
            answerText = answer == null ? null : answer.answer();
            succeeded = true;
        } catch (BusinessException ex) {
            // 已知业务失败（模型不可用、Agent 未发布、知识库被删……）：码与文案都来自既有错误码表，
            // 前端据此能区分"再等等"与"去改配置"。
            errorCode = ex.getCode();
            errorMessage = truncate(ex.getMessage());
        } catch (Exception ex) {
            log.warn("自动化执行失败 automationId={} trigger={}", automation.getId(), triggerType, ex);
            errorCode = ErrorCode.INTERNAL_ERROR.getCode();
            errorMessage = "自动化执行失败，请查看服务端日志";
        } finally {
            // 失败前的模型调用也是真花了钱，采集到多少记多少
            usage = AutomationUsageBridge.finish();
        }

        finishRun(run, succeeded, answerText, errorCode, errorMessage, usage);
        bookkeeping(automation, succeeded, triggerType);
        return toResponse(run);
    }

    /** 组装执行用的 Agent 定义；任务指定了知识库时覆盖检索范围（其余字段沿用 Agent 自身）。 */
    private AgentDefinition resolveDefinition(Long userId, AutomationPO automation) {
        AgentDefinition base = automation.getAgentId() == null
                ? agentService.resolveDefault(userId)
                : agentService.requireDefinition(userId, automation.getAgentId());
        List<Long> kbIds = AutomationKbIds.parse(automation.getKbIds());
        if (kbIds.isEmpty()) {
            return base;
        }
        return new AgentDefinition(
                base.agentId(),
                base.code(),
                base.name(),
                base.systemPrompt(),
                base.modelRole(),
                base.temperature(),
                kbIds,
                base.toolCodes(),
                base.answerPolicy(),
                base.builtin());
    }

    private void finishRun(AutomationRunPO run, boolean succeeded, String answer,
                           Integer errorCode, String errorMessage, AutomationUsageBridge.Usage usage) {
        LocalDateTime now = LocalDateTime.now();
        run.setStatus((succeeded ? AutomationRunStatus.SUCCEEDED : AutomationRunStatus.FAILED).name());
        run.setAnswer(answer);
        run.setErrorCode(errorCode);
        run.setErrorMessage(errorMessage);
        if (usage != null) {
            run.setPromptTokens(toInt(usage.promptTokens()));
            run.setCompletionTokens(toInt(usage.completionTokens()));
            run.setTotalTokens(toInt(usage.totalTokens()));
        }
        run.setFinishedAt(now);
        if (run.getStartedAt() != null) {
            run.setDurationMs(Duration.between(run.getStartedAt(), now).toMillis());
        }
        run.setUpdatedAt(now);
        runMapper.updateById(run);
    }

    /**
     * 回写任务的运行摘要与重试排期。
     *
     * <p>注意这里<b>读一次当前行</b>再写 next_run_at：调度器抢占时已把 next_run_at 推到下一跳，
     * 拿调用方手里那份旧 PO 回写会把抢占结果覆盖掉（任务下一分钟又跑一次）。
     */
    private void bookkeeping(AutomationPO automation, boolean succeeded, String triggerType) {
        LocalDateTime now = LocalDateTime.now();
        automationMapper.updateLastRun(
                automation.getId(),
                now,
                (succeeded ? AutomationRunStatus.SUCCEEDED : AutomationRunStatus.FAILED).name(),
                now);

        int previous = automation.getRetryCount() == null ? 0 : automation.getRetryCount();
        if (succeeded) {
            if (previous != 0) {
                rewriteScheduling(automation.getId(), 0, null, now);
            }
            return;
        }

        boolean scheduled = !"MANUAL".equals(triggerType);
        if (!scheduled) {
            // 手动触发失败：既不排重试，也不动重试计数——
            // 用户手动点的那一次不该消耗"到点触发"的重试预算，否则点两次失败后
            // 第二天的定时任务就因为"历史欠账"被提前放弃重试了。
            return;
        }

        int retries = Math.min(previous + 1, Math.max(maxRetries, 0));
        if (previous + 1 <= maxRetries) {
            LocalDateTime retryAt = retryDelayMs == 0 ? now : now.plusNanos(retryDelayMs * 1_000_000L);
            automationMapper.updateScheduling(automation.getId(), retryAt, retries, now);
            log.info("自动化失败，{}ms 后重试 automationId={} retry={}/{}",
                    retryDelayMs, automation.getId(), retries, maxRetries);
            return;
        }
        // 不再重试：保住调度器写好的下一跳，只更新重试计数
        rewriteScheduling(automation.getId(), retries, null, now);
    }

    /**
     * 只改 retry_count，next_run_at 沿用库里的当前值。
     *
     * @param nextRunAt null 表示"从库里读当前值"
     */
    private void rewriteScheduling(Long automationId, int retryCount, LocalDateTime nextRunAt, LocalDateTime now) {
        AutomationPO current = automationMapper.selectById(automationId);
        if (current == null) {
            return;
        }
        automationMapper.updateScheduling(
                automationId,
                nextRunAt == null ? current.getNextRunAt() : nextRunAt,
                retryCount,
                now);
    }

    private AutomationRunResponse toResponse(AutomationRunPO run) {
        return new AutomationRunResponse(
                run.getId(),
                run.getAutomationId(),
                run.getTriggerType(),
                run.getStatus(),
                run.getAnswer(),
                run.getErrorCode(),
                run.getErrorMessage(),
                run.getPromptTokens(),
                run.getCompletionTokens(),
                run.getTotalTokens(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getDurationMs());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MESSAGE_MAX ? value : value.substring(0, ERROR_MESSAGE_MAX);
    }

    private static Integer toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
