package com.lky.nexus.app.agent.model;

import java.util.Locale;

/**
 * 客户端提交的审批范围（P0，§六）：{@code once | always | deny}。
 *
 * <p>它<b>不是</b> {@code ai_tool_grant.scope}：这里包含 {@code deny} 与 {@code once}，
 * 而长期授权表只允许 {@code ALWAYS}——两者的取值集合刻意不同，
 * 免得"拒绝"被误写进授权表。
 */
public enum ApprovalScope {

    /** 只放行当前这一次调用，不产生任何持久化授权。 */
    ONCE,

    /** 放行当前调用，并写入长期授权（后续同工具不再询问）。 */
    ALWAYS,

    /** 拒绝当前调用；不写授权，并把"用户拒绝"作为 observation 回灌模型。 */
    DENY;

    public static ApprovalScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("审批范围不能为空（once / always / deny）");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ApprovalScope value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的审批范围：" + raw + "（应为 once / always / deny）");
    }
}
