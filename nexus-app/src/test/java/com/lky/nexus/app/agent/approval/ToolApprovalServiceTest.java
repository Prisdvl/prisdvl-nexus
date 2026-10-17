package com.lky.nexus.app.agent.approval;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批状态机的边界（P0 §六）：超时、非法状态、重复决策、并发竞争、越权。
 *
 * <p>为什么这些不放进 {@code ToolApprovalFlowTest}：超时要真的等、并发竞争要真的抢，
 * 放在集成测试里要么慢（默认超时 5 分钟）要么不稳定（靠调度碰运气）。
 * 这里用 mock 的 Mapper 把"另一个事务先赢了"这类时序变成确定性输入，
 * 断言的仍然是 Service 的状态判定与错误码。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolApprovalService：超时 / 状态 / 重复决策 / 竞争 / 越权")
class ToolApprovalServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long RUN_ID = 7L;
    private static final Long APPROVAL_ID = 11L;

    @Mock
    private AgentRunMapper runMapper;

    @Mock
    private ToolApprovalMapper approvalMapper;

    @Mock
    private ToolInvocationAuditService auditService;

    @Mock
    private ToolGrantService grantService;

    private ToolApprovalService service;

    @BeforeEach
    void setUp() {
        // 50ms 超时：这一条用例要验的就是"等不到人怎么办"，没必要真等 5 分钟
        service = new ToolApprovalService(runMapper, approvalMapper, auditService, grantService,
                Duration.ofMillis(50));
    }

    @Test
    @DisplayName("等待超时：不执行工具，审批记 EXPIRED，运行按未执行落定（绝不默认放行）")
    void timeoutExpiresApprovalAndDoesNotExecute() {
        ToolApprovalService.Ticket ticket = new ToolApprovalService.Ticket(
                RUN_ID, APPROVAL_ID, 13L, new CompletableFuture<>());

        ToolApprovalService.Outcome outcome = service.await(ticket);

        assertThat(outcome.execute()).isFalse();
        assertThat(outcome.status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(outcome.observation()).contains("超时").contains("未执行");
        verify(approvalMapper).decide(eq(APPROVAL_ID), eq(ApprovalStatus.EXPIRED.name()),
                isNull(), anyString(), any(LocalDateTime.class));
        // 运行按"未执行"落定（mock 里查不到运行，所以只验证它真的去查了）
        verify(runMapper).selectById(RUN_ID);
    }

    @Test
    @DisplayName("不在 WAITING_APPROVAL 的运行不接受审批：5013（状态类拒绝用 409）")
    void decideOnRunningRunIsRejected() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(USER_ID, AgentRunStatus.RUNNING));

        assertThatThrownBy(() -> service.decide(USER_ID, RUN_ID, true, ApprovalScope.ONCE, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.AGENT_RUN_STATE_INVALID.getCode()));
        verify(approvalMapper, never()).decide(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("没有待决策审批：5014（已处理 / 已失效，不是参数错误）")
    void decideWithoutPendingApprovalIsRejected() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(USER_ID, AgentRunStatus.WAITING_APPROVAL));
        when(approvalMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.decide(USER_ID, RUN_ID, true, ApprovalScope.ONCE, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.APPROVAL_NOT_PENDING.getCode()));
    }

    @Test
    @DisplayName("重复决策：原子更新影响 0 行 → 5014，不会出现第二次执行")
    void duplicateDecisionIsRejected() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(USER_ID, AgentRunStatus.WAITING_APPROVAL));
        ToolApprovalPO pending = pendingApproval();
        when(approvalMapper.selectList(any())).thenReturn(List.of(pending));
        when(approvalMapper.decide(eq(APPROVAL_ID), anyString(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.decide(USER_ID, RUN_ID, true, ApprovalScope.ONCE, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.APPROVAL_NOT_PENDING.getCode()));
        verify(grantService, never()).grantAlways(any(), anyString());
    }

    @Test
    @DisplayName("并发竞争：运行已被取消（CAS 失败）时审批无效，工具不执行")
    void decisionLosesToConcurrentCancel() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(USER_ID, AgentRunStatus.WAITING_APPROVAL));
        when(approvalMapper.selectList(any())).thenReturn(List.of(pendingApproval()));
        when(approvalMapper.decide(eq(APPROVAL_ID), anyString(), any(), any(), any())).thenReturn(1);
        // 另一个事务已经把运行改成 CANCELLED ⇒ 这次 CAS 拿到 0 行
        when(runMapper.compareAndSetStatus(eq(RUN_ID), eq(AgentRunStatus.WAITING_APPROVAL.name()),
                anyString(), any(LocalDateTime.class))).thenReturn(0);

        // 用 once 而不是 always：这里要验的是"CAS 输了就不再执行"，
        // 而 always 的授权写入在同一个事务里（输了一起回滚），不该拿来当断言对象
        assertThatThrownBy(() -> service.decide(USER_ID, RUN_ID, true, ApprovalScope.ONCE, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.AGENT_RUN_STATE_INVALID.getCode()));
    }

    @Test
    @DisplayName("越权：别人的 run 一律 5012（不存在与不属于我不可区分）")
    void otherUsersRunIsNotFound() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(OTHER_USER_ID, AgentRunStatus.RUNNING));

        assertThatThrownBy(() -> service.requireOwned(USER_ID, RUN_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.AGENT_RUN_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("取消幂等：已经是 CANCELLED 的运行再取消一次也是成功，且不重复改状态")
    void cancelIsIdempotent() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(USER_ID, AgentRunStatus.CANCELLED));

        assertThat(service.cancelRun(USER_ID, RUN_ID)).isTrue();
        verify(runMapper, never()).compareAndSetStatus(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("取消后不再放行任何工具调用：executionBlockedReason 给出明确原因")
    void cancelledRunBlocksFurtherToolCalls() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(USER_ID, AgentRunStatus.RUNNING));
        assertThat(service.executionBlockedReason(RUN_ID)).isNull();

        when(runMapper.selectById(RUN_ID)).thenReturn(run(USER_ID, AgentRunStatus.CANCELLED));
        assertThat(service.executionBlockedReason(RUN_ID)).contains("运行已取消");
    }

    private AgentRunPO run(Long userId, AgentRunStatus status) {
        AgentRunPO run = new AgentRunPO();
        run.setId(RUN_ID);
        run.setUserId(userId);
        run.setStatus(status.name());
        return run;
    }

    private ToolApprovalPO pendingApproval() {
        ToolApprovalPO approval = new ToolApprovalPO();
        approval.setId(APPROVAL_ID);
        approval.setRunId(RUN_ID);
        approval.setUserId(USER_ID);
        approval.setToolCode("test_write_tool");
        approval.setStatus(ApprovalStatus.PENDING.name());
        return approval;
    }
}
