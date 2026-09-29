package com.lky.nexus.app.auth.controller;

import com.lky.nexus.app.auth.model.dto.AuthTokenResponse;
import com.lky.nexus.app.auth.model.dto.LoginRequest;
import com.lky.nexus.app.auth.model.dto.RegisterRequest;
import com.lky.nexus.app.auth.model.dto.UserInfoResponse;
import com.lky.nexus.app.auth.service.AuthService;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（无需登录即可访问，见 SecurityConfig 的 PUBLIC_ENDPOINTS）。
 *
 * <p>Controller 只做三件事：接参数、调 Service、包 Result。
 * 任何校验规则、密码逻辑、令牌签发都不允许出现在这一层（对应 docs/architecture.md §3 的分层红线）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<UserInfoResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }
}
