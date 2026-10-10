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
 */
@Mapper
public interface StatsMapper {

    /** 近 N 天每天的消息数（无消息的日期不会返回，由 Service 补零）。 */
    @Select("""
            SELECT DATE(created_at) AS day, COUNT(*) AS cnt
            FROM chat_message
            WHERE user_id = #{userId}
              AND created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            GROUP BY DATE(created_at)
            ORDER BY day
            """)
    List<DayCount> messageCountByDay(@Param("userId") Long userId, @Param("days") int days);

    /** 近 N 天的最近活动：新建会话 + 导入任务，按时间倒序合并。 */
    @Select("""
            (SELECT 'CHAT' AS type, id AS refId, title AS label, created_at AS at
               FROM chat_conversation
              WHERE user_id = #{userId} AND deleted = 0
              ORDER BY created_at DESC LIMIT #{limit})
            UNION ALL
            (SELECT 'IMPORT' AS type, id AS refId, source_name AS label, created_at AS at
               FROM kb_import_task
              WHERE user_id = #{userId}
              ORDER BY created_at DESC LIMIT #{limit})
            ORDER BY at DESC LIMIT #{limit}
            """)
    List<ActivityRow> recentActivity(@Param("userId") Long userId, @Param("limit") int limit);

    @Data
    class DayCount {
        private LocalDate day;
        private long cnt;
    }

    @Data
    class ActivityRow {
        private String type;
        private Long refId;
        private String label;
        private LocalDateTime at;
    }
}