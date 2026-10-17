package com.lky.nexus.app.agent.model.dto;

import com.lky.nexus.app.agent.model.entity.AgentRunStepPO;

import java.time.LocalDateTime;

/**
 * 一步运行轨迹（P0 §五）。
 *
 * <p>{@code THINK} 只是阶段标记（"开始运行 / 第 n 轮"），<b>不含任何模型内部推理原文</b>；
 * {@code input} / {@code output} 是脱敏摘要。这两条是"轨迹可审计"与"思维链不外泄"
 * 之间那条线，写在这里免得以后有人觉得"多存点更好排查"。
 */
public record AgentRunStepResponse(
        String id,
        Integer seq,
        String type,
        String toolCode,
        String input,
        String output,
        String status,
        LocalDateTime createdAt) {

    public static AgentRunStepResponse of(AgentRunStepPO step) {
        return new AgentRunStepResponse(
                String.valueOf(step.getId()),
                step.getSeq(),
                step.getStepType(),
                step.getToolCode(),
                step.getInputDigest(),
                step.getOutputDigest(),
                step.getStatus(),
                step.getCreatedAt());
    }
}
