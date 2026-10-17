package com.lky.nexus.app.agent.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 审批决策（{@code POST /api/agent/runs/{runId}/approval}，P0 §六）。
 *
 * <p>刻意只接受三个字段：工具代码、权限等级、用户 id 都不在请求体里——
 * 它们要么来自待审批记录，要么来自 JWT。允许客户端提交权限等级，
 * 等于把"这个工具危险不危险"的决定权交回给调用方。
 */
public record ApprovalDecisionRequest(
        @NotNull Boolean approved,
        @Size(max = 16) String scope,
        @Size(max = 500) String reason) {

    /** scope 缺省时按最保守的语义处理（拒绝则 deny，批准则 once）。 */
    public String scopeOrDefault() {
        if (scope != null && !scope.isBlank()) {
            return scope;
        }
        return Boolean.TRUE.equals(approved) ? "once" : "deny";
    }
}
