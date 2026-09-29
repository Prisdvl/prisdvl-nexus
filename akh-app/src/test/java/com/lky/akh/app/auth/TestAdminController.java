package com.lky.akh.app.auth;

import com.lky.akh.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅测试用：模拟"管理员专属接口"，用于验证 {@code /api/admin/**} 的 RBAC 规则。
 *
 * <p>真实的管理员接口在 Phase 10（观测看板）与 Phase 11（用户管理）落地。
 */
@RestController
@RequestMapping("/api/admin")
class TestAdminController {

    @GetMapping("/ping")
    Result<String> ping() {
        return Result.ok("pong");
    }
}
