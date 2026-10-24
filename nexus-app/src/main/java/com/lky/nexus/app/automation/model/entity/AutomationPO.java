package com.lky.nexus.app.automation.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自动化任务定义（表 {@code automation}）。
 *
 * <p>{@code scheduleType} 对应列 {@code schedule_type}（列名刻意不叫 {@code schedule}：
 * 这个词在部分数据库方言里是保留字，改名比加引号省事）；
 * {@code cron} 存的是<b>真实生效</b>的表达式，固定频率时由 {@code AutomationSchedule} 派生。
 *
 * <p>{@code nextRunAt} 既是"下次什么时候跑"，也是多实例调度的 CAS 基准值——
 * 调度器读它、抢它、再改成下一跳，抢不到就说明另一个实例已经领走了。
 */
@Data
@TableName("automation")
public class AutomationPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所有者：所有查询都必须带上它。 */
    private Long userId;

    private String name;

    /** 到点交给 Agent 的问题。 */
    private String prompt;

    /** 执行的 Agent；null = 用该用户的默认 Agent。 */
    private Long agentId;

    /** 逗号分隔的知识库 ID；空 = 不覆盖 Agent 自身的绑定。 */
    private String kbIds;

    /** HOURLY / DAILY / WEEKLY / CUSTOM（{@code AutomationSchedule}）。 */
    private String scheduleType;

    private String cron;

    /** 1 启用 / 0 暂停。用 Integer 而非 Boolean：与既有表的 TINYINT 惯例一致。 */
    private Integer enabled;

    private LocalDateTime nextRunAt;

    private LocalDateTime lastRunAt;

    /** SUCCEEDED / FAILED；从未跑过时为 null。 */
    private String lastStatus;

    /** 连续失败次数：成功清零，用于失败重试退避。 */
    private Integer retryCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
