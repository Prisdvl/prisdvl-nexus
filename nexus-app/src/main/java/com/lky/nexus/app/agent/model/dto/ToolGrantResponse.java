package com.lky.nexus.app.agent.model.dto;

import com.lky.nexus.app.agent.model.entity.ToolGrantPO;

import java.time.LocalDateTime;

/** 用户工具授权视图（P0 §八）。 */
public record ToolGrantResponse(
        String id,
        String toolCode,
        String scope,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ToolGrantResponse of(ToolGrantPO grant) {
        return new ToolGrantResponse(
                String.valueOf(grant.getId()),
                grant.getToolCode(),
                grant.getScope(),
                grant.getCreatedAt(),
                grant.getUpdatedAt());
    }
}
