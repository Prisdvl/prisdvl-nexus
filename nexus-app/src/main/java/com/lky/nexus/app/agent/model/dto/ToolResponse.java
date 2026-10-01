package com.lky.nexus.app.agent.model.dto;

import com.lky.nexus.ai.agent.ToolDefinition;

/**
 * 工具视图（{@code GET /api/tools}，4.3）。
 *
 * <p>字段刻意<b>不含</b> {@code jsonSchema}：它是给 MCP 客户端与模型看的机器格式，
 * 界面用不上，塞进列表响应只会让前端多一个"要不要解析它"的坑（真要看得去 {@code tools/list}）。
 *
 * <p>{@code enabled} 是<b>用户覆盖后</b>的真实状态（没有覆盖行就是 true）；
 * {@code builtin=true} 的工具不可删除，但可以停用。
 */
public record ToolResponse(
        String code,
        String name,
        String description,
        String source,
        String category,
        boolean enabled,
        boolean builtin) {

    public static ToolResponse of(ToolDefinition definition) {
        return new ToolResponse(
                definition.code(),
                definition.name(),
                definition.description(),
                definition.source().name(),
                definition.category().name(),
                definition.enabled(),
                definition.builtin());
    }
}
