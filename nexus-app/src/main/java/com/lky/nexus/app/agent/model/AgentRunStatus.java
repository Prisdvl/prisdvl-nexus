package com.lky.nexus.app.agent.model;

import java.util.Locale;

/**
 * Agent 运行状态（P0，§五）。
 *
 * <p><b>刻意没有 {@code parse} 之外的宽松行为</b>：状态是从数据库列读出来的，
 * 未知值必须炸出来而不是回落成 RUNNING——把"库里有个我们不认识的状态"当成"还在跑"，
 * 会让一个卡死的运行永远显示进行中，而且没有任何日志。
 */
public enum AgentRunStatus {

    RUNNING,

    /** 有一个工具调用在等人工审批；模型循环暂停在同一处，不是重新开始。 */
    WAITING_APPROVAL,

    DONE,

    FAILED,

    MAX_ITERATIONS,

    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == MAX_ITERATIONS || this == CANCELLED;
    }

    public static AgentRunStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("运行状态不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (AgentRunStatus value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的运行状态：" + raw);
    }
}
