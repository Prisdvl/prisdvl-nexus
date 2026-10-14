package com.lky.nexus.app.auth.controller;

import com.lky.nexus.app.auth.model.dto.AuthTokenResponse;
import com.lky.nexus.app.auth.model.dto.LoginRequest;
import com.lky.nexus.app.auth.model.dto.LogoutRequest;
import com.lky.nexus.app.auth.model.dto.RefreshTokenRequest;
import com.lky.nexus.app.auth.model.dto.RefreshTokenResponse;
import com.lky.nexus.app.auth.model.dto.RegisterRequest;
import com.lky.nexus.app.auth.model.dto.SessionClientInfo;
import com.lky.nexus.app.auth.model.dto.UserInfoResponse;
import com.lky.nexus.app.auth.service.AuthService;
import com.lky.nexus.app.auth.session.RefreshTokenService;
import com.lky.nexus.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（注册 / 登录 / refresh / logout；公开端点见 SecurityConfig）。
 * 忘记密码见 {@link PasswordResetController}。
 *
 * <p>Controller 只做三件事：接参数、调 Service、包 Result。
 * 任何校验规则、密码逻辑、令牌签发都不允许出现在这一层（对应 docs/architecture.md §3 的分层红线）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public Result<UserInfoResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest) {
        return Result.ok(authService.login(request, clientInfo(httpRequest)));
    }

    /**
     * 用 refresh token 换取新的 access + refresh（rotation）。
     *
     * <p>公开端点：access 可能已过期，仅凭 body 中的 refreshToken 鉴权。
     */
    @PostMapping("/refresh")
    public Result<RefreshTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request,
                                                HttpServletRequest httpRequest) {
        return Result.ok(refreshTokenService.refresh(request.refreshToken(), clientInfo(httpRequest)));
    }

    /**
     * 当前设备退出：撤销对应 refresh session。
     *
     * <p>公开端点：access 过期时仍应能退出。Access JWT 本身不立即删除，依赖 exp / token_version。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody LogoutRequest request) {
        refreshTokenService.logout(request.refreshToken());
        return Result.ok();
    }

    /** IP 取 {@link HttpServletRequest#getRemoteAddr()}（与验邮 / 重置一致，不信任 X-Forwarded-For）。 */
    private static SessionClientInfo clientInfo(HttpServletRequest request) {
        return SessionClientInfo.of(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
}
