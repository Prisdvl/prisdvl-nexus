package com.lky.nexus.app.automation.model.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 局部更新自动化任务（{@code PUT /api/automations/{id}}）。
 *
 * <p>语义与仓里其它 {@code PUT} 一致：<b>null = 不改</b>。三个例外要单独记：
 * <ul>
 *   <li>{@code kbIds}：传空数组 = 清空绑定，传 null = 不改；</li>
 *   <li>{@code agentId}：要解绑到"默认 Agent"不能传 null（那是"不改"），
 *       必须传 {@code useDefaultAgent: true}——与会话那边 {@code resetAgent} 同一个理由：
 *       一个 null 同时表示"别动"和"清空"，调用方迟早会传错；</li>
 *   <li>{@code enabled}：由 false 改 true 时服务端按 {@code now} 重算 next_run_at，
 *       不会把停机期间欠下的都补跑一遍。</li>
 * </ul>
 *
 * @param name           任务名；null = 不改
 * @param prompt         提示词；null = 不改
 * @param agentId        换 Agent；null = 不改
 * @param useDefaultAgent true = 解绑到默认 Agent（优先于 agentId）
 * @param kbIds          覆盖检索范围；空数组 = 清空，null = 不改
 * @param schedule       改频率；null = 不改
 * @param cron           自定义 cron（schedule=custom 时必填）
 * @param enabled        启停；null = 不改
 */
public record UpdateAutomationRequest(
        @Size(max = 128, message = "任务名最长 128 个字符")
        String name,

        @Size(max = 4000, message = "prompt 最长 4000 个字符")
        String prompt,

        Long agentId,

        Boolean useDefaultAgent,

        List<Long> kbIds,

        String schedule,

        @Size(max = 64, message = "cron 最长 64 个字符")
        String cron,

        Boolean enabled) {
}
