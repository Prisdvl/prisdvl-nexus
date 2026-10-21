package com.lky.nexus.app.auth.controller;

import com.lky.nexus.app.auth.model.dto.ChangePasswordRequest;
import com.lky.nexus.app.auth.model.dto.QuotaResponse;
import com.lky.nexus.app.auth.model.dto.UpdateProfileRequest;
import com.lky.nexus.app.auth.model.dto.UserInfoResponse;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.auth.service.AuthService;
import com.lky.nexus.app.security.QuotaService;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.quota.StorageQuotaService;
import com.lky.nexus.knowledge.quota.StorageUsage;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（需要已认证）。
 *
 * <p><b>本控制器所有接口都没有 userId 参数</b>：用户身份只从 JWT 的 {@code sub} 取
 * （见 {@link CurrentUser}）。从请求参数或请求体里接收 userId 是水平越权的典型来源——
 * 攻击者改一个数字就能操作别人的账号（ADR-018）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;
    private final QuotaService quotaService;
    private final StorageQuotaService storageQuotaService;

    public UserController(AuthService authService, QuotaService quotaService,
                          StorageQuotaService storageQuotaService) {
        this.authService = authService;
        this.quotaService = quotaService;
        this.storageQuotaService = storageQuotaService;
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

    /**
     * 修改个人资料（5.6）：昵称 / 邮箱。
     *
     * <p>字段语义（半更新）：不传 = 不修改，传空串 = 清空。详见 {@link UpdateProfileRequest}。
     */
    @PutMapping("/me")
    public Result<UserInfoResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(authService.updateProfile(CurrentUser.requireId(), request));
    }

    /**
     * 修改密码（5.6）。
     *
     * <p><b>成功后本次会话也会失效</b>：我们吊销的是这个用户的**全部**令牌，
     * 包含发起本次请求的这一支。这是"改密码 = 所有设备下线"的正确语义，
     * 前端必须明说（提示"请用新密码重新登录"）而不是让用户以为自己被莫名踢了。
     *
     * <p>原密码错误返回 <b>1001 / 400</b> 而不是 2006 / 401，理由见
     * {@code AuthServiceImpl.changePassword}：401 会触发前端全局拦截器清 Token 跳登录页，
     * 把一个输入错误升级成会话中断。
     */
    @PutMapping("/me/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.requireId(), request);
        return Result.ok();
    }

    /**
     * 当前用户的当日额度（5.6，只读）。
     *
     * <p>把"还剩多少"变成用户看得见的东西：配额被 403 拦住之前，
     * 界面上的那一行（"今日 12/200 次 · 3.4k/200k tokens"）就是它。
     *
     * <p>这里是 {@link QuotaService} 而不是 {@code AuthService}：额度不是认证概念，
     * 没必要为了少一个依赖就往 AuthService 上挂一个纯转发的口子。
     */
    @GetMapping("/me/quota")
    public Result<QuotaResponse> quota() {
        QuotaService.Snapshot snapshot = quotaService.snapshot(CurrentUser.requireId());
        return Result.ok(new QuotaResponse(
                snapshot.requestUsed(), snapshot.requestLimit(),
                snapshot.tokenUsed(), snapshot.tokenLimit(),
                snapshot.resetAt()));
    }

    /**
     * 当前用户的存储用量（只读）。
     *
     * <p>直接把知识库模块的 {@link StorageUsage} 当响应体，不再包一层同构 DTO：
     * 它就是"用量 + 上限"的只读快照，多写一个字段名相同的类只会让两处定义慢慢漂移。
     *
     * <p>上限为 {@code <= 0} 表示不限，前端据此不画进度条（而不是画一条满格）。
     */
    @GetMapping("/me/storage")
    public Result<StorageUsage> storage() {
        return Result.ok(storageQuotaService.usage(CurrentUser.requireId()));
    }
}
