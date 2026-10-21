package com.lky.nexus.app.stats;

import java.util.List;

/**
 * 工作台（Dashboard）汇总响应：一次返回趋势、热力、Token 热力、最近活动四块数据，由前端直接渲染。
 *
 * <p>不可变 DTO（record）。补零、合并等业务逻辑都在 {@link StatsService}，这里只承载最终形态，
 * 不写任何业务逻辑；字段含义见各嵌套 record。
 */
public record StatsSummary(
        List<TrendPoint> trend,
        List<TrendPoint> heat,
        List<TrendPoint> tokenHeat,
        List<ActivityItem> activity) {

    /** 某一天的数量（趋势/热力共用；tokenHeat 里 count 是当天 Token 合计）。 */
    public record TrendPoint(String day, long count) {
    }

    /** 一条最近活动。 */
    public record ActivityItem(String type, String refId, String label, String at) {
    }
}
