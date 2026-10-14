package com.lky.nexus.app.stats;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台统计接口。
 *
 * <p>用户维度由服务端从令牌解析（{@link CurrentUser#requireId()}），
 * <b>不接受任何前端传入的 userId</b>。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    /** 注入 {@link StatsService}，由 Spring 完成。 */
    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 工作台汇总：趋势（近 14 天）+ 热力（近 30 天）+ 最近活动（近 8 条）。
     *
     * @return 当前用户的汇总数据（用户身份取自令牌，不接受前端传入的 userId）
     */
    @GetMapping("/summary")
    public Result<StatsSummary> summary() {
        return Result.ok(statsService.summary(CurrentUser.requireId()));
    }
}