package com.lky.nexus.app.stats;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的用量」逐日 Token 消耗（只读）。
 *
 * <p>路由挂在 {@code /api/users/me} 下而不是 {@code /api/stats} 下：这条数据是用户自己的
 * 账单视图，和 {@code /api/users/me/quota}（今日额度）、{@code /me/storage}（存储占用）
 * 是同一组只读接口，前端的用量页也是一起取的。聚合逻辑仍留在 {@link StatsService}：
 * 路径按"谁的数据"分组，实现按"怎么算"分组。
 *
 * <p><b>没有 userId 参数</b>：用户身份只从令牌取（{@link CurrentUser}），与其余
 * {@code /api/users/me} 接口一致。
 */
@RestController
@RequestMapping("/api/users/me/stats")
public class DailyTokenStatsController {

    private final StatsService statsService;

    /** 注入 {@link StatsService}，由 Spring 完成。 */
    public DailyTokenStatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 近 {@code days} 天（默认 365）的逐日 Token 消耗。
     *
     * @param days 窗口天数，1–365；缺省 365。越界返回 1001，不做静默截断
     * @return 连续日轴（无消息的日子 tokens=0）+ 窗口两端日期
     */
    @GetMapping("/daily-tokens")
    public Result<DailyTokenSeries> dailyTokens(
            @RequestParam(name = "days", defaultValue = "365") int days) {
        return Result.ok(statsService.dailyTokens(CurrentUser.requireId(), days));
    }
}
