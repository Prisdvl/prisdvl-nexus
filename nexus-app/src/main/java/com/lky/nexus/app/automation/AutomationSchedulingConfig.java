package com.lky.nexus.app.automation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 Spring 的调度器（本仓此前没有 {@code @Scheduled} 基础设施，这是第一个）。
 *
 * <p>单独成一个配置类而不是标在 {@code NexusApplication} 上，是为了让"要不要跑调度"
 * 能按环境开关：测试 profile 把它关掉（{@code nexus.automation.scheduler-enabled: false}），
 * 否则测试线程会被定时扫描干扰，且"到点触发"用例需要自己决定何时扫（直接调
 * {@code automationScheduler.sweepOnce()}）。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        value = "nexus.automation.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AutomationSchedulingConfig {
}
