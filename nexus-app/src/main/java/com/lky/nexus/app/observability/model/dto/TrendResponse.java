package com.lky.nexus.app.observability.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量趋势（{@code GET /api/observability/trend}）。
 *
 * <p>分桶在应用层做（不写 SQL 的 {@code DATE_FORMAT} / {@code TIMESTAMPADD}）：
 * MySQL 与测试库 H2 的日期函数互不兼容，写进 SQL 只会换来"一边能跑一边红"。
 *
 * <p><b>空桶会被补零</b>：只有真实数据点的折线图，在"某天完全没人用"时会连成一跳，
 * 看起来像持续有人用。补零之后，"这段真的没量"是一眼可见的。
 *
 * @param by      分桶粒度：{@code hour} 或 {@code day}
 * @param from    窗口起点（对齐到桶边界）
 * @param to      窗口终点
 * @param points  按时间升序的桶
 */
public record TrendResponse(
        String by,
        LocalDateTime from,
        LocalDateTime to,
        List<TrendPointResponse> points) {
}
