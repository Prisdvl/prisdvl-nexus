package com.lky.nexus.app.stats;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台聚合读模型。
 *
 * <p>只读、按 {@code user_id} 过滤（调用方从 {@code CurrentUser} 取，绝不接受前端传入的 userId，
 * 否则就是一个越权读别人统计的口子）。
 *
 * <p>为什么用可变 POJO 而不是 record：MyBatis 映射到 record 走构造器按位置匹配，
 * 多一列就静默错位；setter + 列名匹配更耐改（与 AgentUsageRow 的教训一致）。
 *
 * <p>⚠️ <b>别名不能叫 {@code day} / {@code at} 这类词</b>：{@code DAY} 是 H2 的保留字，
 * 同一条 SQL 在 MySQL 上能跑、在 H2 上直接语法错误（主测试套件跑在 H2 上）。
 * 这里统一用 {@code stat_day} / {@code act_*} 前缀规避，靠
 * {@code map-underscore-to-camel-case} 映射到 POJO 字段。
 */
@Mapper
public interface StatsMapper {

    /** 近 N 天每天的消息数（无消息的日期不会返回，由 Service 补零）。 */
    /**
     * 近 N 天每天的用量：消息条数 + 消息 Token 合计（无数据的日子不会返回，由 Service 补零）。
     *
     * <p>条数与 Token 合并成一次查询：两者同源同窗口，拆成两条 SQL 只会多一次表扫描，
     * 还会有「两条查询跨过零点、窗口对不齐」的隐患。{@code total_tokens} 允许为 NULL
     * （流式中断的消息可能没写回用量），所以用 {@code COALESCE} 归零。
     */
    @Select("""
            SELECT CAST(created_at AS DATE) AS stat_day,
                   COUNT(*) AS msg_count,
                   COALESCE(SUM(total_tokens), 0) AS token_count
            FROM chat_message
            WHERE user_id = #{userId}
              AND created_at >= #{since}
            GROUP BY CAST(created_at AS DATE)
            ORDER BY stat_day
            """)
    List<DayCount> dailyUsage(@Param("userId") Long userId, @Param("since") LocalDate since);

    /** 近 N 天的最近活动：新建会话 + 导入任务，按时间倒序合并。 */
    @Select("""
            (SELECT 'CHAT' AS act_type, id AS ref_id, title AS act_label, created_at AS act_at
               FROM chat_conversation
              WHERE user_id = #{userId} AND deleted = 0
              ORDER BY created_at DESC LIMIT #{limit})
            UNION ALL
            (SELECT 'IMPORT' AS act_type, id AS ref_id, source_name AS act_label, created_at AS act_at
               FROM kb_import_task
              WHERE user_id = #{userId}
              ORDER BY created_at DESC LIMIT #{limit})
            ORDER BY act_at DESC LIMIT #{limit}
            """)
    List<ActivityRow> recentActivity(@Param("userId") Long userId, @Param("limit") int limit);

    @Data
    class DayCount {
        private LocalDate statDay;
        private long msgCount;
        /** 当天消息的 Token 合计（{@code chat_message.total_tokens} 求和）。 */
        private long tokenCount;
    }

    @Data
    class ActivityRow {
        private String actType;
        private Long refId;
        private String actLabel;
        private LocalDateTime actAt;
    }
}