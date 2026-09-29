package com.lky.akh.app.auth.controller;

import com.lky.akh.app.auth.model.dto.UserInfoResponse;
import com.lky.akh.app.auth.security.CurrentUser;
import com.lky.akh.app.auth.service.AuthService;
import com.lky.akh.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（需要已认证）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 当前登录用户信息。
     *
     * <p>注意这里<b>没有</b> userId 参数：用户身份只从 JWT 里取（见 {@link CurrentUser}），
     * 从请求参数取 userId 是水平越权漏洞的典型来源。
     */
    @GetMapping("/me")
    public Result<UserInfoResponse> me() {
        return Result.ok(authService.currentUser(CurrentUser.requireId()));
    }
}
