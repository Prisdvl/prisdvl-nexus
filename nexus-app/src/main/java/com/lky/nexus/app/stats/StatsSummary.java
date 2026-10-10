package com.lky.nexus.app.stats;

import java.util.List;

/** 工作台汇总响应。 */
public record StatsSummary(
        List<TrendPoint> trend,
        List<TrendPoint> heat,
        List<ActivityItem> activity) {

    /** 某一天的数量（趋势/热力共用）。 */
    public record TrendPoint(String day, long count) {
    }

    /** 一条最近活动。 */
    public record ActivityItem(String type, String refId, String label, String at) {
    }
}