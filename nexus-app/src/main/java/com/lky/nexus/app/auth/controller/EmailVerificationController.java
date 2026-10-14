package com.lky.nexus.app.auth.controller;

import com.lky.nexus.app.auth.model.dto.UserInfoResponse;
import com.lky.nexus.app.auth.model.dto.VerifyEmailRequest;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.auth.service.EmailVerificationService;
import com.lky.nexus.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登录用户验证当前绑定邮箱（Phase 2）。
 *
 * <p>两个接口均需认证；用户身份只从 {@link CurrentUser#requireId()} 取。
 * 不接受客户端传入 userId / 任意目标邮箱。不在公开端点白名单中。
 */
@RestController
@RequestMapping("/api/users/me/email/verification")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * 向当前绑定邮箱发送验证码。
     *
     * <p>IP 取 {@link HttpServletRequest#getRemoteAddr()}（与注册限流一致，不信任 X-Forwarded-For）。
     */
    @PostMapping("/send")
    public Result<Void> send(HttpServletRequest request) {
        emailVerificationService.send(CurrentUser.requireId(), request.getRemoteAddr());
        return Result.ok();
    }

    /** 提交验证码，将当前绑定邮箱标记为已验证。 */
    @PostMapping
    public Result<UserInfoResponse> verify(@Valid @RequestBody VerifyEmailRequest body) {
        return Result.ok(emailVerificationService.verify(CurrentUser.requireId(), body.code()));
    }
}
