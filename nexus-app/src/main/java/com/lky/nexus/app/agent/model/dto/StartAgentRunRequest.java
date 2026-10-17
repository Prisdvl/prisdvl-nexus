package com.lky.nexus.app.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起一次 Agent 运行（{@code POST /api/agent/runs}，P0）。
 *
 * <p>{@code agentId} / {@code conversationId} 用 String：主键在对外契约里一律是字符串
 * （雪花 ID 超过 JS 安全整数范围），服务端再解析成 Long。
 * <b>没有 {@code userId} 字段</b>：归属只能来自 JWT——请求体能带 userId 的接口，
 * 迟早会有人带别人的。
 */
public record StartAgentRunRequest(
        @Size(max = 32) String agentId,
        @Size(max = 32) String conversationId,
        @NotBlank @Size(max = 8000) String question) {
}
