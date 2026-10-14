package com.lky.nexus.app.auth.controller;

import com.lky.nexus.app.auth.model.dto.UserSessionResponse;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.auth.session.RefreshTokenService;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户的登录设备会话管理（需 access JWT）。
 *
 * <p>身份只从 {@link CurrentUser} 取；路径里的 session id 必须属于当前用户，
 * 否则 {@link RefreshTokenService#revokeSession} 返回会话不存在（防水平越权）。
 */
@RestController
@RequestMapping("/api/users/me/sessions")
public class UserSessionController {

    private final RefreshTokenService refreshTokenService;

    public UserSessionController(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 列出未撤销且未过期的设备会话。
     *
     * <p>可选请求头 {@code X-Refresh-Token}：仅用于标记 {@code current=true}；
     * 列表本身只含 {@link com.lky.nexus.app.auth.security.CurrentUser} 的会话，
     * 他人 refresh 无法越权查看。不传则全部为 false。绝不在响应中回传 token / hash。
     *
     * <p>注意：该头含长期凭证，接入层 / 反向代理若记录请求头需脱敏；应用日志不得打印其值。
     */
    @GetMapping
    public Result<List<UserSessionResponse>> list(
            @RequestHeader(value = "X-Refresh-Token", required = false) String refreshToken) {
        return Result.ok(refreshTokenService.listSessions(CurrentUser.requireId(), refreshToken));
    }

    /** 指定设备退出：只能撤销自己的会话。 */
    @DeleteMapping("/{id}")
    public Result<Void> revokeOne(@PathVariable("id") Long id) {
        refreshTokenService.revokeSession(CurrentUser.requireId(), id);
        return Result.ok();
    }

    /** 全部设备退出（方案 A：批量 revoke 未撤销会话）。 */
    @PostMapping("/revoke-all")
    public Result<Void> revokeAll() {
        refreshTokenService.revokeAllSessions(CurrentUser.requireId());
        return Result.ok();
    }
}
