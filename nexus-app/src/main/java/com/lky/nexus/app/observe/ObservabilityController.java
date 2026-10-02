package com.lky.nexus.app.observe;

import com.lky.nexus.common.observe.ObservabilityRegistry;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.observe.model.AgentUsageResponse;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 观测接口（Phase 10）：一眼看清"哪些静默路径在计数"。
 *
 * <p>两个端点的数据<b>来源不同，不要混着看</b>：
 * <ul>
 *   <li>{@code /summary} 读的是进程内的 {@link ObservabilityRegistry}——自本次进程启动以来
 *       的累计，重启即归零，且不含任何归属维度（它是全局的）；</li>
 *   <li>{@code /agents} 读的是数据库里的 {@code chat_message}（5.4）——跨重启的既成事实，
 *       按用户隔离。</li>
 * </ul>
 * 同一个数字在两个端点对不上是正常的（一个是"这次进程见过多少"，一个是"库里有多少"）。
 * 想加一个"全局按 Agent 聚合"的端点时必须先解决归属问题，不能把这里改成不带 user_id 的版本。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilityRegistry registry;
    private final AgentUsageService agentUsageService;

    public ObservabilityController(ObservabilityRegistry registry,
                                   AgentUsageService agentUsageService) {
        this.registry = registry;
        this.agentUsageService = agentUsageService;
    }

    /** 计数快照：降级/不可用/失败次数，全部是"每次真实发生"的累计。 */
    @GetMapping("/summary")
    public Result<Map<String, Long>> summary() {
        CurrentUser.requireId();
        return Result.ok(registry.snapshot());
    }

    /**
     * 按 Agent 的用量（5.4）：哪个 Agent 在被真的用、用了多少、失败多少、最后一次是什么时候。
     *
     * <p>归属取自当前登录用户，<b>不接受任何入参</b>——这是"只读自己的"最不容易写错的形式：
     * 没有可以填错的参数，也就没有越权查询的入口。
     */
    @GetMapping("/agents")
    public Result<List<AgentUsageResponse>> agents() {
        Long userId = CurrentUser.requireId();
        return Result.ok(agentUsageService.usageByAgent(userId));
    }
}