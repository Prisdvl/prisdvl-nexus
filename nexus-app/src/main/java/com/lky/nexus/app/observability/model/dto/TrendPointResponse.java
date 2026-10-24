package com.lky.nexus.app.observability.model.dto;

import java.time.LocalDateTime;

/**
 * 趋势里的一个桶。
 *
 * @param bucket       桶起点（ISO 字符串，如 {@code 2026-02-01T09:00:00}）；按天时是当日零点
 * @param calls        该桶内模型调用次数
 * @param tokens       该桶内 token 合计
 * @param failures     该桶内失败调用数
 * @param avgLatencyMs 该桶内平均耗时；该桶无调用时为 0
 */
public record TrendPointResponse(
        LocalDateTime bucket,
        long calls,
        long tokens,
        long failures,
        long avgLatencyMs) {
}
