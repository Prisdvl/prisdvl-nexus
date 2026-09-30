package com.lky.nexus.app.agent;

import java.util.List;

/**
 * Agent 回答（Phase 5）。
 *
 * @param answer    最终回答
 * @param toolCalls 工具调用痕迹——"用户可控"的透明性来源
 * @param usedTools 是否真的发生了工具调用
 */
public record AgentAnswer(String answer, List<AgentContext.ToolCallRecord> toolCalls, boolean usedTools) {
}