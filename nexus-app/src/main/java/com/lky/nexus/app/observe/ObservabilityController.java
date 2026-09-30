package com.lky.nexus.app.observe;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 观测接口（Phase 10）：一眼看清"哪些静默路径在计数"。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilityRegistry registry;

    public ObservabilityController(ObservabilityRegistry registry) {
        this.registry = registry;
    }

    /** 计数快照：降级/不可用/失败次数，全部是"每次真实发生"的累计。 */
    @GetMapping("/summary")
    public Result<Map<String, Long>> summary() {
        CurrentUser.requireId();
        return Result.ok(registry.snapshot());
    }
}