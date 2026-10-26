package com.lky.nexus.app.plugin.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一个外部 MCP server 的状态（列表与详情共用）。
 *
 * <p>刻意<b>没有</b> {@code authHeader} 字段，只有 {@code hasAuthHeader}：
 * 凭据只进不出是这一层的默认姿态（沿用 {@code user_llm_config} 的做法）。
 *
 * <p>probe 的结果（{@code lastOkAt / lastError}）与调用结果（{@code lastCall*}）<b>分开</b>：
 * 前者是"配的 server 通不通"，后者是"工具这次跑没跑成"。合成一个字段，
 * 页面就只能说一句笼统的话，用户也无从判断该改配置还是该重试。
 *
 * @param id            主键
 * @param name          展示名
 * @param slug          工具前缀：{@code mcp__<slug>__<tool>}
 * @param transport     {@code http} / {@code stdio}
 * @param url           transport=http 的 endpoint
 * @param command       transport=stdio 的命令行
 * @param hasAuthHeader 是否存了 Authorization 凭据
 * @param enabled       是否启用（停用后不再向 Agent 注入其工具）
 * @param lastOkAt      最近一次 probe 成功时间；从未成功过则为 null
 * @param lastError     最近一次 probe 失败原因；从未失败过则为 null
 * @param tools         最近一次 tools/list 的结果（可能为空：还没 probe 过，或 server 确实没有工具）
 * @param allowStdio    是否已显式允许在本机执行 {@code command}
 * @param lastCallAt    最近一次<b>工具调用</b>时间（与 probe 无关）
 * @param lastCallTool  最近一次调用的工具名
 * @param lastCallOk    最近一次调用是否成功；从未调用过则为 null
 * @param lastCallError 最近一次调用失败原因（含错误码 9205/9206/9207）
 */
public record McpServerResponse(Long id,
                                String name,
                                String slug,
                                String transport,
                                String url,
                                String command,
                                boolean hasAuthHeader,
                                boolean enabled,
                                LocalDateTime lastOkAt,
                                String lastError,
                                List<McpToolInfo> tools,
                                boolean allowStdio,
                                LocalDateTime lastCallAt,
                                String lastCallTool,
                                Boolean lastCallOk,
                                String lastCallError) {
}
