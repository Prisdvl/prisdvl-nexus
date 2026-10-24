package com.lky.nexus.app.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.automation.model.entity.AutomationPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 自动化任务定义。
 *
 * <p>三个写操作都是显式 SQL 而不是 {@code updateById}：MyBatis-Plus 默认跳过 null 字段，
 * 而"暂停后把 next_run_at 清空"恰恰要写 null——靠默认行为会静默失败（暂停的任务
 * 仍带着旧到期时间，重新启用后立刻补跑一次）。
 */
@Mapper
public interface AutomationMapper extends BaseMapper<AutomationPO> {

    /**
     * 抢占一个到期的任务（多实例安全的关键）。
     *
     * <p>先读出 {@code next_run_at}，再用"它还是原值"作为条件把它推到下一跳：
     * 两个实例同时读到同一个到期值时，只有一条 UPDATE 会命中，
     * 另一个 {@code affected = 0} 直接跳过——不靠锁、不靠 Quartz。
     *
     * @param id               任务 ID
     * @param expectedNextRunAt 读到的到期时刻（CAS 基准）
     * @param nextRunAt         抢占后要写入的下一跳
     * @param now               当前时间（同时写 updated_at）
     * @return 1 = 抢到；0 = 被别人抢走或任务已被停用/删除
     */
    @Update("""
            UPDATE automation
               SET next_run_at = #{nextRunAt},
                   updated_at  = #{now}
             WHERE id            = #{id}
               AND next_run_at   = #{expectedNextRunAt}
               AND enabled       = 1
               AND deleted       = 0
            """)
    int claimDue(@Param("id") Long id,
                 @Param("expectedNextRunAt") LocalDateTime expectedNextRunAt,
                 @Param("nextRunAt") LocalDateTime nextRunAt,
                 @Param("now") LocalDateTime now);

    /** 改写排期与重试计数（允许把 next_run_at 写成 null）。 */
    @Update("""
            UPDATE automation
               SET next_run_at = #{nextRunAt},
                   retry_count = #{retryCount},
                   updated_at  = #{now}
             WHERE id = #{id} AND deleted = 0
            """)
    int updateScheduling(@Param("id") Long id,
                         @Param("nextRunAt") LocalDateTime nextRunAt,
                         @Param("retryCount") int retryCount,
                         @Param("now") LocalDateTime now);

    /**
     * 整体改写一条任务的"定义"字段（PUT 的落点）。
     *
     * <p>为什么不用 {@code updateById} 做局部更新：本接口允许把 {@code agent_id}、
     * {@code kb_ids}、{@code next_run_at} 显式改成 NULL（解绑、清空检索范围、暂停），
     * 而 {@code updateById} 会把这些 null 当"不改"跳过。与其在服务层判断哪个字段能用
     * updateById、哪个得补一条 update，不如一处写成全字段覆盖——调用方负责先把
     * "不改"的字段填成原值。
     */
    @Update("""
            UPDATE automation
               SET name          = #{name},
                   prompt        = #{prompt},
                   agent_id      = #{agentId},
                   kb_ids        = #{kbIds},
                   schedule_type = #{scheduleType},
                   cron          = #{cron},
                   enabled       = #{enabled},
                   next_run_at   = #{nextRunAt},
                   updated_at    = #{now}
             WHERE id = #{id} AND deleted = 0
            """)
    int updateDefinition(@Param("id") Long id,
                         @Param("name") String name,
                         @Param("prompt") String prompt,
                         @Param("agentId") Long agentId,
                         @Param("kbIds") String kbIds,
                         @Param("scheduleType") String scheduleType,
                         @Param("cron") String cron,
                         @Param("enabled") int enabled,
                         @Param("nextRunAt") LocalDateTime nextRunAt,
                         @Param("now") LocalDateTime now);

    /** 记一次运行的结果摘要（列表页只读这两列，不必 join 运行表）。 */
    @Update("""
            UPDATE automation
               SET last_run_at = #{lastRunAt},
                   last_status = #{lastStatus},
                   updated_at  = #{now}
             WHERE id = #{id}
            """)
    int updateLastRun(@Param("id") Long id,
                      @Param("lastRunAt") LocalDateTime lastRunAt,
                      @Param("lastStatus") String lastStatus,
                      @Param("now") LocalDateTime now);
}
