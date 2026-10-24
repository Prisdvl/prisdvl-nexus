package com.lky.nexus.app.automation.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动化任务的视图（列表与详情共用）。
 *
 * <p>为什么连 {@code agentName} / {@code kbNames} 一起返回：列表页要显示"用哪个 Agent、
 * 搜哪些库"，只给 ID 的话前端还得再拉两遍全量列表来对名字——那两次请求既慢又容易和
 * 当前页的行对不上（翻页时并发改名）。
 *
 * @param id         任务 ID（Long 在 JSON 里是字符串，前端按 string 建模）
 * @param name       任务名
 * @param prompt     提示词
 * @param agentId    指定 Agent；null = 默认 Agent
 * @param agentName  指定 Agent 的名称（已删则 null）
 * @param kbIds      覆盖检索范围的知识库 ID；空 = 沿用 Agent 绑定
 * @param kbNames    上述知识库的名称（已删的跳过）
 * @param schedule   hourly / daily / weekly / custom（小写，与前端字面量一致）
 * @param cron       真实生效的 cron
 * @param enabled    是否启用
 * @param nextRunAt  下次触发时刻；暂停时为 null
 * @param lastRunAt  最近一次运行时刻；从未跑过为 null
 * @param lastStatus 最近一次运行状态：SUCCEEDED / FAILED；从未跑过为 null
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 */
public record AutomationResponse(
        Long id,
        String name,
        String prompt,
        Long agentId,
        String agentName,
        List<Long> kbIds,
        List<String> kbNames,
        String schedule,
        String cron,
        boolean enabled,
        LocalDateTime nextRunAt,
        LocalDateTime lastRunAt,
        String lastStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
