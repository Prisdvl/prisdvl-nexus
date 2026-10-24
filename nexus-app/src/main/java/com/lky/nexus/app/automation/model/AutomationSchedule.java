package com.lky.nexus.app.automation.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 自动化排期：三种固定频率 + 自定义 cron。
 *
 * <p>为什么固定频率也要折算成 cron 落库：调度器只认一种"下一次是什么时候"的算法。
 * 两套算法（枚举分支 + cron 解析）迟早会在边界上给出不同答案（整点、周一边界、月末），
 * 那种差异只会在"某天该跑没跑"时才被发现。所以 HOURLY/DAILY/WEEKLY 只是三个 cron 预设，
 * 库里的 {@code cron} 列存的永远是真实生效的表达式。
 *
 * <p>对外是 5 段 cron（分 时 日 月 周，与前端表单一致）；Spring 的 {@link CronExpression}
 * 要 6 段（秒在最前），所以解析前补一个秒位 {@code 0}。传 6 段也接受，不再补。
 */
public enum AutomationSchedule {

    /** 每小时整点。 */
    HOURLY("0 * * * *"),
    /** 每天 09:00。 */
    DAILY("0 9 * * *"),
    /** 每周一 10:00（周字段 1 = 周一，0/7 = 周日）。 */
    WEEKLY("0 10 * * 1"),
    /** 用户自填 cron。 */
    CUSTOM(null);

    private final String presetCron;

    AutomationSchedule(String presetCron) {
        this.presetCron = presetCron;
    }

    /** 固定频率的预设 cron；CUSTOM 为 null（用用户填的那条）。 */
    public String presetCron() {
        return presetCron;
    }

    /**
     * 解析请求里的 schedule 值（大小写不敏感）。
     *
     * @param raw hourly / daily / weekly / custom
     * @return 对应枚举
     * @throws BusinessException 9003（值是 null、空串或拼错了）
     */
    public static AutomationSchedule parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("schedule 不能为空，只能是 hourly / daily / weekly / custom");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("schedule 只能是 hourly / daily / weekly / custom，收到：" + raw);
        }
    }

    /** 真正生效的 cron：固定频率取预设，CUSTOM 取用户自填。 */
    public String effectiveCron(String storedCron) {
        return presetCron != null ? presetCron : storedCron;
    }

    /**
     * 计算 {@code from} 之后的下一次触发时刻。
     *
     * @param storedCron 库里存的 cron（CUSTOM 时必须非空）
     * @param from       基准时刻
     * @return 严格晚于 {@code from} 的时刻
     * @throws BusinessException 9003（cron 非法，或表达式在本世纪内没有下一跳）
     */
    public LocalDateTime nextRunAfter(String storedCron, LocalDateTime from) {
        String cron = effectiveCron(storedCron);
        if (cron == null || cron.isBlank()) {
            throw invalid("自定义排期必须提供 cron");
        }
        LocalDateTime base = from == null ? LocalDateTime.now() : from;
        CronExpression expression = parseOrThrow(cron);
        LocalDateTime next = expression.next(base);
        if (next == null) {
            throw invalid("cron 表达式没有下一次触发时间：" + cron);
        }
        return next;
    }

    /**
     * 校验并解析 cron（5 段或 6 段）。
     *
     * @param cron 表达式
     * @return Spring 的解析结果
     * @throws BusinessException 9003（段数不对或语法非法）
     */
    public static CronExpression parseOrThrow(String cron) {
        if (cron == null || cron.isBlank()) {
            throw invalid("cron 不能为空");
        }
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            throw invalid("cron 需要 5 段（分 时 日 月 周），收到 " + fields.length + " 段：" + cron);
        }
        String normalized = fields.length == 5 ? "0 " + String.join(" ", fields) : String.join(" ", fields);
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException ex) {
            throw invalid("cron 非法：" + cron + "（" + ex.getMessage() + "）");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.AUTOMATION_SCHEDULE_INVALID, message);
    }
}
