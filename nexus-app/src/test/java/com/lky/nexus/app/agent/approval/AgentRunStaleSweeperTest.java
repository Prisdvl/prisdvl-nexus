package com.lky.nexus.app.agent.approval;

import com.lky.nexus.app.agent.mapper.AgentRunMapper;
import com.lky.nexus.app.agent.mapper.ToolApprovalMapper;
import com.lky.nexus.app.agent.mapper.ToolInvocationMapper;
import com.lky.nexus.app.agent.model.AgentRunStatus;
import com.lky.nexus.app.agent.model.ApprovalStatus;
import com.lky.nexus.app.agent.model.ToolInvocationStatus;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import com.lky.nexus.app.agent.model.entity.ToolApprovalPO;
import com.lky.nexus.app.agent.model.entity.ToolInvocationPO;
import com.lky.nexus.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动清理：进程重启后没有线程接管的运行必须被收口成明确的失败。
 *
 * <p>这些用例断言的是「不留僵尸」而不是「能恢复」——恢复需要持久化工具原始参数，
 * 见 {@link AgentRunStaleSweeper} 的类注释。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunStaleSweeperTest {

    private static final Long USER_ID = 900_000_000_000_000_001L;

    @Autowired
    private AgentRunStaleSweeper sweeper;

    @Autowired
    private AgentRunMapper runMapper;

    @Autowired
    private ToolApprovalMapper approvalMapper;

    @Autowired
    private ToolInvocationMapper invocationMapper;

    @Test
    @DisplayName("RUNNING / WAITING_APPROVAL 被收口成 FAILED，终态运行一个都不动")
    void sweepsInterruptedRunsOnly() {
        Long running = newRun(AgentRunStatus.RUNNING);
        Long waiting = newRun(AgentRunStatus.WAITING_APPROVAL);
        Long done = newRun(AgentRunStatus.DONE);
        Long failed = newRun(AgentRunStatus.FAILED);
        Long cancelled = newRun(AgentRunStatus.CANCELLED);

        Long approvalId = newPendingApproval(waiting);
        Long invocationId = newPendingInvocation(running);

        int closed = sweeper.sweep();
        assertThat(closed).as("只该收口 RUNNING + WAITING_APPROVAL 这两个").isEqualTo(2);

        assertClosed(running);
        assertClosed(waiting);

        // 终态既不能被改状态，也不能被写上"服务重启"这类错误信息
        assertThat(runMapper.selectById(done).getStatus()).isEqualTo(AgentRunStatus.DONE.name());
        assertThat(runMapper.selectById(done).getErrorMsg()).isNull();
        assertThat(runMapper.selectById(failed).getStatus()).isEqualTo(AgentRunStatus.FAILED.name());
        assertThat(runMapper.selectById(cancelled).getStatus()).isEqualTo(AgentRunStatus.CANCELLED.name());

        // 待决策审批作废：否则它会永远挂在"等你决策"上
        ToolApprovalPO approval = approvalMapper.selectById(approvalId);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.EXPIRED.name());
        assertThat(approval.getDecidedAt()).isNotNull();

        // 待审批的审计行收口成 CANCELLED（工具没执行过，不是执行失败）
        ToolInvocationPO invocation = invocationMapper.selectById(invocationId);
        assertThat(invocation.getStatus()).isEqualTo(ToolInvocationStatus.CANCELLED.name());
        assertThat(invocation.getFinishedAt()).isNotNull();
        assertThat(invocation.getErrorCode()).isEqualTo(ErrorCode.AGENT_RUN_STATE_INVALID.getCode());
    }

    @Test
    @DisplayName("再跑一次没有可收口的：幂等，不会重复改写终态")
    void sweepIsIdempotent() {
        Long waiting = newRun(AgentRunStatus.WAITING_APPROVAL);
        newPendingApproval(waiting);

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(sweeper.sweep()).as("第二次已经没有在途运行").isZero();

        AgentRunPO after = runMapper.selectById(waiting);
        assertThat(after.getStatus()).isEqualTo(AgentRunStatus.FAILED.name());
        assertThat(after.getErrorMsg()).isEqualTo(AgentRunStaleSweeper.INTERRUPTED_MESSAGE);
    }

    private void assertClosed(Long runId) {
        AgentRunPO run = runMapper.selectById(runId);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED.name());
        assertThat(run.getErrorCode()).isEqualTo(ErrorCode.AGENT_RUN_STATE_INVALID.getCode());
        assertThat(run.getErrorMsg()).isEqualTo(AgentRunStaleSweeper.INTERRUPTED_MESSAGE);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    private Long newRun(AgentRunStatus status) {
        AgentRunPO run = new AgentRunPO();
        run.setUserId(USER_ID);
        run.setQuestion("启动清理用例");
        run.setStatus(status.name());
        run.setMaxIterations(8);
        run.setIterationCount(0);
        run.setModelRole("chat.agent");
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run.getId();
    }

    private Long newPendingApproval(Long runId) {
        ToolApprovalPO approval = new ToolApprovalPO();
        approval.setRunId(runId);
        approval.setUserId(USER_ID);
        approval.setToolCode("test_write_tool");
        approval.setArgumentsDigest("digest");
        approval.setStatus(ApprovalStatus.PENDING.name());
        approval.setCreatedAt(LocalDateTime.now());
        approvalMapper.insert(approval);
        return approval.getId();
    }

    private Long newPendingInvocation(Long runId) {
        ToolInvocationPO invocation = new ToolInvocationPO();
        invocation.setRunId(runId);
        invocation.setUserId(USER_ID);
        invocation.setToolCode("test_write_tool");
        invocation.setPermission("WRITE");
        invocation.setArgumentsDigest("digest");
        invocation.setStatus(ToolInvocationStatus.PENDING_APPROVAL.name());
        invocation.setStartedAt(LocalDateTime.now());
        invocationMapper.insert(invocation);
        return invocation.getId();
    }
}
