package com.lky.nexus.app.auth.controller;

import com.lky.nexus.app.auth.model.dto.PasswordResetConfirmRequest;
import com.lky.nexus.app.auth.model.dto.PasswordResetRequest;
import com.lky.nexus.app.auth.service.PasswordResetService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 忘记密码 / 邮箱验证码重置（公开接口，见 SecurityConfig 的 PUBLIC_ENDPOINTS）。
 *
 * <p>不签发令牌；用户身份只由请求体中的规范化邮箱在服务端解析。
 */
@RestController
@RequestMapping("/api/auth/password/reset")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /**
     * 请求重置验证码。
     *
     * <p>IP 取 {@link HttpServletRequest#getRemoteAddr()}（与验邮发送一致，不信任 X-Forwarded-For）。
     * 格式合法时：统一计入 IP 发送配额；未注册 / 未验证 / 冷却中 / 发信成功均返回同一模糊成功提示。
     * 仅独立 IP 上限触发时返回 429（与账户是否存在无关）。
     */
    @PostMapping("/request")
    public Result<Void> request(@Valid @RequestBody PasswordResetRequest body,
                                HttpServletRequest request) {
        passwordResetService.requestReset(body.email(), request.getRemoteAddr());
        return new Result<>(ErrorCode.SUCCESS.getCode(),
                PasswordResetService.REQUEST_ACK_MESSAGE, null);
    }

    /** 提交验证码与新密码，完成重置。 */
    @PostMapping("/confirm")
    public Result<Void> confirm(@Valid @RequestBody PasswordResetConfirmRequest body) {
        passwordResetService.confirmReset(body.email(), body.code(), body.newPassword());
        return new Result<>(ErrorCode.SUCCESS.getCode(),
                PasswordResetService.CONFIRM_OK_MESSAGE, null);
    }
}
