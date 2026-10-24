package com.lky.nexus.app.plugin.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次 probe 的结果（{@code POST /api/mcp-servers/{id}/probe}）。
 *
 * <p><b>为什么失败也返回 200</b>：探测失败是一种<b>正常的观测结果</b>，不是一个
 * "请求写错了"的 4xx，也不是"服务不可用"的 5xx——外部 server 挂了是我们的上游问题，
 * 而调用方要的正是"它为什么连不上"这句话。把它包成异常只会让前端拿不到 {@code error} 文案，
 * 于是又退化成"静默当成没有工具"。
 *
 * @param ok      probe 是否成功
 * @param error   失败原因（成功时为 null）：连不上 / 401 / 协议不兼容 / 超时，都要能读出是哪一种
 * @param tools   成功时拉到的工具列表；失败时为空列表
 * @param tookMs  本次探测耗时
 * @param lastOkAt 该 server 最近一次成功时间（失败时保留历史值，便于判断"是不是刚坏"）
 */
public record McpProbeResponse(boolean ok,
                               String error,
                               List<McpToolInfo> tools,
                               long tookMs,
                               LocalDateTime lastOkAt) {
}
