package com.lky.nexus.app.notify.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通道的对外视图。
 *
 * <p>不回传 {@code secret} 明文，只回 {@code hasSecret} —— 前端要展示的是「已配置」，不是密钥本身。
 */
public record NotifyChannelResponse(
        Long id,
        String name,
        String type,
        String target,
        boolean hasSecret,
        List<String> events,
        boolean enabled,
        String lastStatus,
        String lastError,
        LocalDateTime lastAt
) {
}
