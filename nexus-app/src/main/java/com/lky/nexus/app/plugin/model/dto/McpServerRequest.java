package com.lky.nexus.app.plugin.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 修改一个外部 MCP server。
 *
 * <p>{@code authHeader} 是<b>明文</b>（例如 {@code Bearer sk-xxx}）：服务端加密后写进
 * {@code auth_header_cipher}，回显只给 {@code hasAuthHeader}。
 * 传 null 表示"不改动已保存的凭据"，空串表示"清掉"。
 *
 * <p>{@code transport} 只有 {@code http}（streamable HTTP）与 {@code stdio}（本机子进程）两种；
 * 后者受 {@code nexus.mcp.stdio-enabled} 开关约束（默认关闭，见 application.yml 的说明）。
 *
 * @param name       展示名，也是工具名前缀 slug 的来源
 * @param transport  {@code http} / {@code stdio}；null 视为 {@code http}
 * @param url        transport=http 时的 endpoint
 * @param command    transport=stdio 时的命令行（如 {@code npx -y @modelcontextprotocol/server-filesystem /tmp}）
 * @param authHeader Authorization 头原文；null = 不改，空串 = 清除
 * @param enabled    是否启用；null 视为 true
 */
public record McpServerRequest(
        @NotBlank @Size(max = 64) String name,
        @Size(max = 16) String transport,
        @Size(max = 512) String url,
        @Size(max = 512) String command,
        @Size(max = 400) String authHeader,
        Boolean enabled) {
}
