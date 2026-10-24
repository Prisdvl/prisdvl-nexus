package com.lky.nexus.app.automation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建自动化任务（{@code POST /api/automations}）。
 *
 * <p>{@code schedule} 只收 hourly / daily / weekly / custom（大小写不敏感）；
 * {@code cron} 只在 custom 时必填，固定频率会被服务端改写成对应的预设表达式。
 *
 * @param name    任务名（必填，≤128）
 * @param prompt  到点交给 Agent 的问题（必填，≤4000）
 * @param agentId 执行的 Agent；null/省略 = 用该用户的默认 Agent
 * @param kbIds   覆盖检索范围的知识库；省略或空 = 沿用 Agent 自身绑定
 * @param schedule hourly / daily / weekly / custom
 * @param cron     自定义 cron（5 段：分 时 日 月 周）
 * @param enabled  是否启用；null 按 true 处理
 */
public record CreateAutomationRequest(
        @NotBlank(message = "任务名不能为空")
        @Size(max = 128, message = "任务名最长 128 个字符")
        String name,

        @NotBlank(message = "prompt 不能为空")
        @Size(max = 4000, message = "prompt 最长 4000 个字符")
        String prompt,

        Long agentId,

        List<Long> kbIds,

        @NotBlank(message = "schedule 不能为空")
        String schedule,

        @Size(max = 64, message = "cron 最长 64 个字符")
        String cron,

        Boolean enabled) {
}
