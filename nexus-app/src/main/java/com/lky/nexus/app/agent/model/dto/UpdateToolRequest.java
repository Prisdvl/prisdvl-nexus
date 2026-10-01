package com.lky.nexus.app.agent.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 启停工具请求（{@code PUT /api/tools/{code}}，4.3）。
 *
 * <p>刻意用 {@code Boolean} + {@code @NotNull} 而不是 {@code boolean}：包装类型能为 null，
 * 于是"忘了传"会被校验拦下（400），而不是被默认成 false（= 静默停用工具）。
 */
public record UpdateToolRequest(
        @NotNull(message = "enabled 不能为空")
        Boolean enabled) {
}
