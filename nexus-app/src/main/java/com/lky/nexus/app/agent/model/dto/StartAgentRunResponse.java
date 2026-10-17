package com.lky.nexus.app.agent.model.dto;

/**
 * 发起运行的响应：只回 runId（P0 §五）。
 *
 * <p>刻意只回一个 id：运行是异步的，状态与轨迹各自有查询接口
 * （{@code GET /api/agent/runs/{runId}} 与 {@code /steps}）。把状态塞进创建响应里，
 * 会诱导调用方把它当成"已经跑完"。
 */
public record StartAgentRunResponse(String runId) {
}
