package com.lky.nexus.app.stats;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台统计。
 *
 * <p>趋势与热力都是"近 N 天消息数"，差别只在窗口长度（14 / 30）。
 * <b>SQL 只返回有数据的日期</b>，这里负责把空缺日期补 0 —— 否则前端折线会把
 * "没有那天"画成"那天不存在"，视觉上等于把稀疏数据拉平。
 */
@Service
public class StatsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final StatsMapper statsMapper;

    public StatsService(StatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    /**
     * 汇总当前用户的工作台数据。
     *
     * @param userId 当前用户 ID（由服务端从令牌解析，绝不接受前端传入）
     * @return 趋势 / 热力 / 最近活动三块数据；空缺日期已补 0
     */
    @Transactional(readOnly = true)
    public StatsSummary summary(Long userId) {
        return new StatsSummary(
                fill(userId, 14),
                fill(userId, 30),
                activity(userId, 8));
    }

    /** 近 days 天的逐日计数，缺失日期补 0，按日期升序。 */
    private List<StatsSummary.TrendPoint> fill(Long userId, int days) {
        Map<String, Long> byDay = new HashMap<>();
        for (StatsMapper.DayCount row : statsMapper.messageCountByDay(userId, LocalDate.now().minusDays(days - 1))) {
            byDay.put(row.getStatDay().format(DAY), row.getMsgCount());
        }
        List<StatsSummary.TrendPoint> out = new ArrayList<>(days);
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            String key = today.minusDays(i).format(DAY);
            out.add(new StatsSummary.TrendPoint(key, byDay.getOrDefault(key, 0L)));
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