package com.lky.nexus.app.notify;

/**
 * 通知通道类型。
 *
 * <p>{@code WEBHOOK} 的 target 是 URL（要求 http/https、生产禁内网），{@code EMAIL} 的 target
 * 是邮箱地址。类型决定 target 怎么校验、正文怎么发 —— 所以两者存在同一张表，靠这一列分派。
 */
public enum NotifyChannelType {

    WEBHOOK,
    EMAIL;

    public static NotifyChannelType of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("通知通道类型不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的通知通道类型：" + raw);
        }
    }
}
