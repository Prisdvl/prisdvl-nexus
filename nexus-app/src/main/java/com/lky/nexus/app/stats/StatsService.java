package com.lky.nexus.app.stats;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * 工作台统计。
 *
 * <p>趋势与热力都是"近 N 天消息数"，差别只在窗口长度（14 / 30）；Token 热力是同一个窗口里
 * 每天的消息 Token 合计 —— 三块都从同一次查询里切出来。
 * <b>SQL 只返回有数据的日期</b>，这里负责把空缺日期补 0 —— 否则前端折线会把
 * "没有那天"画成"那天不存在"，视觉上等于把稀疏数据拉平。
 */
@Service
public class StatsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 热力窗口（天）。趋势只有 14 天，从这份更长的结果里切尾部即可，不必再查一次。 */
    private static final int HEAT_DAYS = 30;
    private static final int TREND_DAYS = 14;

    private final StatsMapper statsMapper;

    public StatsService(StatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    /**
     * 汇总当前用户的工作台数据。
     *
     * @param userId 当前用户 ID（由服务端从令牌解析，绝不接受前端传入）
     * @return 趋势 / 热力 / Token 热力 / 最近活动；空缺日期已补 0
     */
    @Transactional(readOnly = true)
    public StatsSummary summary(Long userId) {
        Map<String, StatsMapper.DayCount> byDay = new HashMap<>();
        for (StatsMapper.DayCount row : statsMapper.dailyUsage(userId, LocalDate.now().minusDays(HEAT_DAYS - 1))) {
            byDay.put(row.getStatDay().format(DAY), row);
        }
        return new StatsSummary(
                series(byDay, TREND_DAYS, StatsMapper.DayCount::getMsgCount),
                series(byDay, HEAT_DAYS, StatsMapper.DayCount::getMsgCount),
                series(byDay, HEAT_DAYS, StatsMapper.DayCount::getTokenCount),
                activity(userId, 8));
    }

    /**
     * 近 days 天的逐日数值，缺失日期补 0，按日期升序。
     *
     * @param pick 从当天的行里取哪一列（消息条数 / Token 合计）
     */
    private List<StatsSummary.TrendPoint> series(
            Map<String, StatsMapper.DayCount> byDay, int days, ToLongFunction<StatsMapper.DayCount> pick) {
        List<StatsSummary.TrendPoint> out = new ArrayList<>(days);
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            String key = today.minusDays(i).format(DAY);
            StatsMapper.DayCount row = byDay.get(key);
            out.add(new StatsSummary.TrendPoint(key, row == null ? 0L : pick.applyAsLong(row)));
        }
        return out;
    }

    private List<StatsSummary.ActivityItem> activity(Long userId, int limit) {
        List<StatsSummary.ActivityItem> out = new ArrayList<>();
        for (StatsMapper.ActivityRow row : statsMapper.recentActivity(userId, limit)) {
            out.add(new StatsSummary.ActivityItem(
                    row.getActType(),
                    String.valueOf(row.getRefId()),
                    row.getActLabel(),
                    row.getActAt() == null ? null : row.getActAt().toString()));
        }
        return out;
    }
}
