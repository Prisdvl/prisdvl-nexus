package com.lky.nexus.app.plugin.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新 MCP server：<b>每个字段都可有可无</b>，缺省即"别动这一项"。
 *
 * <p>为什么不能复用 {@link McpServerRequest}：创建时 {@code name} 是必填，
 * 而"只把一个 server 停用"这种更新根本不会带 name —— 用创建 DTO 接更新，
 * 前端每次改开关都得把整份配置回传一遍，漏一个字段就是一个 400。
 *
 * <p>三条特殊语义（详见 {@code McpServerService.update}）：
 * {@code authHeader} 为 {@code null} = 别动凭据、空串 = 清除凭据；
 * {@code name} 改名不会改 {@code slug}（它已经刻在工具 code 里）；
 * {@code allowStdio} 为 {@code null} = 别动，"取消勾选"必须显式传 false。
 */
public record UpdateMcpServerRequest(
        @Size(max = 64) String name,
        @Size(max = 16) String transport,
        @Size(max = 512) String url,
        @Size(max = 512) String command,
        @Size(max = 400) String authHeader,
        Boolean enabled,
        Boolean allowStdio) {
}
