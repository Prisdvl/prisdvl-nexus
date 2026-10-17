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
        String permission,
        boolean enabled,
        boolean builtin) {

    /** 把工具的领域定义转成界面视图（enabled 已是用户覆盖后的真实状态）。 */
    public static ToolResponse of(ToolDefinition definition) {
        return of(definition, null);
    }

    /**
     * 带<b>数据库里实际生效的权限</b>的视图。
     *
     * <p>{@code definition.permission()} 是代码声明的默认值，只用于首次注册；
     * 管理员把某个工具改成 DANGEROUS 之后，界面与执行拦截必须看到同一个值——
     * 否则会出现"列表写着 READ、执行时按 DANGEROUS 拦"这种最容易被当成 bug 的不一致。
     */
    public static ToolResponse of(ToolDefinition definition, String effectivePermission) {
        return new ToolResponse(
                definition.code(),
                definition.name(),
                definition.description(),
                definition.source().name(),
                definition.category().name(),
                effectivePermission == null ? definition.permission().name() : effectivePermission,
                definition.enabled(),
                definition.builtin());
    }
}
