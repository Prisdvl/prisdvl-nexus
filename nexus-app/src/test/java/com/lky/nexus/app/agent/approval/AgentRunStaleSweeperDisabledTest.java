package com.lky.nexus.app.agent.approval;

import com.lky.nexus.app.agent.mapper.AgentRunMapper;
import com.lky.nexus.app.agent.model.AgentRunStatus;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多实例部署时的逃生门：关掉启动清理后，遗留运行必须原样留着。
 *
 * <p>多实例共享同一库时，别的实例上"合法运行中"的行看上去与"进程崩溃遗留"一模一样，
 * 启动清理无法区分——所以给一个能关的开关，而不是让它成为不可协商的行为。
 */
@SpringBootTest(properties = "nexus.agent.sweep-stale-runs-on-startup=false")
@ActiveProfiles("test")
class AgentRunStaleSweeperDisabledTest {

    @Autowired
    private AgentRunStaleSweeper sweeper;

    @Autowired
    private AgentRunMapper runMapper;

    @Test
    @DisplayName("开关关掉时不碰任何在途运行")
    void doesNothingWhenDisabled() {
        AgentRunPO run = new AgentRunPO();
        run.setUserId(900_000_000_000_000_002L);
        run.setQuestion("关闭清理用例");
        run.setStatus(AgentRunStatus.WAITING_APPROVAL.name());
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        assertThat(sweeper.sweep()).isZero();

        AgentRunPO after = runMapper.selectById(run.getId());
        assertThat(after.getStatus()).isEqualTo(AgentRunStatus.WAITING_APPROVAL.name());
        assertThat(after.getErrorMsg()).isNull();
    }
}
