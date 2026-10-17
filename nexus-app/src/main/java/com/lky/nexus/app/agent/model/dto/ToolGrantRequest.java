package com.lky.nexus.app.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 写入长期授权（{@code PUT /api/users/me/tool-grants}，P0 §八）。
 *
 * <p>{@code scope} 只允许 {@code always}（缺省即 always）：长期授权表不保存
 * {@code once} / {@code deny}——它们不是"长期"的语义。
 */
public record ToolGrantRequest(
        @NotBlank @Size(max = 64) String toolCode,
        @Size(max = 16) String scope) {

    public String scopeOrDefault() {
        return (scope == null || scope.isBlank()) ? "always" : scope;
    }
}
