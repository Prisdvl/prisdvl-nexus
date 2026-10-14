package com.lky.nexus.app.auth.model.dto;

import java.time.LocalDateTime;

/**
 * 当前用户的设备会话（列表接口；绝不包含 token / hash）。
 */
public record UserSessionResponse(
        Long id,
        String device,
        String ip,
        LocalDateTime lastUsedAt,
        LocalDateTime createdAt,
        boolean current) {
}
