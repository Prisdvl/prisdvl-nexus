package com.lky.nexus.app.automation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.automation.mapper.AutomationMapper;
import com.lky.nexus.app.automation.model.entity.AutomationPO;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 到点触发：扫 {@code next_run_at <= now} 的启用任务，抢占后交给 {@link AutomationRunner}。
 *
 * <p>多实例安全靠 <b>单条 UPDATE 的 CAS</b>，不靠分布式锁：
 * <pre>
 * UPDATE automation SET next_run_at = 下一跳
 *  WHERE id = ? AND next_run_at = 抢到的那个到期值 AND enabled = 1 AND deleted = 0
 * </pre>
 * 影响 0 行 = 另一个实例已经把这轮推进了（或用户刚好暂停/改了排期），本实例直接跳过。
 * 这样连"锁租约过期"这种失败模式都没有：谁抢到谁推进，抢占与推进是同一个原子动作。
 *
 * <p>下一跳按"被抢占的到期时刻"计算而不是 {@code now}：否则每次执行都会往后漂一点，
 * 一小时的任务跑一天就偏到别的整点去了。
 */
@Component
public class AutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

    private final AutomationMapper automationMapper;
    private final AutomationService automationService;
    private final AutomationRunner runner;
    private final int batchSize;

    public AutomationScheduler(AutomationMapper automationMapper,
                               AutomationService automationService,
                               AutomationRunner runner,
                               @Value("${nexus.automation.sweep-batch-size:20}") int batchSize) {
        this.automationMapper = automationMapper;
        this.automationService = automationService;
        this.runner = runner;
        this.batchSize = batchSize;
    }

    /**
     * 定时入口（间隔与首次延迟都可配；测试里关掉调度后直接调 {@link #sweepOnce()}）。
     */
    @Scheduled(
            fixedDelayString = "${nexus.automation.sweep-interval-ms:30000}",
            initialDelayString = "${nexus.automation.sweep-initial-delay-ms:15000}")
    public void sweep() {
        try {
            int fired = sweepOnce();
            if (fired > 0) {
                log.info("自动化调度：本轮触发 {} 个任务", fired);
            }
        } catch (Exception ex) {
            // 单轮失败不能让 @Scheduled 线程死掉（它会继续下一轮），但必须留下痕迹
            log.error("自动化调度扫描失败", ex);
        }
    }

    /**
     * 扫一轮并执行到期任务。
     *
     * @return 本轮实际抢到并执行的任务数
     */
    public int sweepOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<AutomationPO> due = automationMapper.selectList(Wrappers.<AutomationPO>lambdaQuery()
                .eq(AutomationPO::getEnabled, 1)
                .isNotNull(AutomationPO::getNextRunAt)
                .le(AutomationPO::getNextRunAt, now)
                .orderByAsc(AutomationPO::getNextRunAt)
                .last("LIMIT " + Math.max(batchSize, 1)));

        int fired = 0;
        for (AutomationPO task : due) {
            try {
                if (claimAndRun(task)) {
                    fired++;
                }
            } catch (Exception ex) {
                // 单个任务的异常不影响同批其他任务
                log.warn("自动化任务执行异常 automationId={}", task.getId(), ex);
            }
        }
        return fired;
    }

    /** 抢占一条到期任务并执行；抢不到返回 false。 */
    private boolean claimAndRun(AutomationPO task) {
        LocalDateTime expectedNextRunAt = task.getNextRunAt(); // 选出来时读到的那个到期值
        LocalDateTime nextRunAt;
        try {
            nextRunAt = automationService.nextRunAfter(task, expectedNextRunAt);
        } catch (BusinessException ex) {
            // 库里的 cron 被改坏（正常写入路径已校验，这里只可能是人工改库）：
            // 跳过并告警，不要每 30 秒重复抢同一行
            log.warn("自动化任务排期无法解析，跳过 automationId={} cron={}", task.getId(), task.getCron());
            return false;
        }
        int claimed = automationMapper.claimDue(
                task.getId(), expectedNextRunAt, millis(nextRunAt), LocalDateTime.now());
        if (claimed == 0) {
            // 别的实例抢到了，或用户刚暂停/改了排期
            return false;
        }
        log.debug("自动化任务抢占成功 automationId={} nextRunAt={}", task.getId(), nextRunAt);
        runner.run(task.getUserId(), task, "SCHEDULED");
        return true;
    }

    /** DATETIME(3) 只存到毫秒，比较值必须同一精度，否则 CAS 永远匹配不上。 */
    private static LocalDateTime millis(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
