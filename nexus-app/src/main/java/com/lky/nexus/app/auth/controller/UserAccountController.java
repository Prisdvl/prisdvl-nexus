package com.lky.nexus.app.auth.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.auth.service.UserAccountDeletionService;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注销当前账号（{@code DELETE /api/users/me}）。
 *
 * <p>单独一个控制器而不是塞进 {@link UserController}：注销是破坏性操作，路由上看得见、
 * 也便于将来给它单独加限流或二次确认。
 *
 * <p><b>没有 userId 参数</b>：只删 JWT 里的那个用户（{@link CurrentUser#requireId()}）。
 * 接收 userId 等于给了「删别人账号」的入口。
 *
 * <p>返回 {@code Result<Void>}（{@code code=0}）而不是裸 204：本项目所有接口都走统一包装，
 * 前端拦截器也只认这层包装，破例会让调用方多一条分支。
 */
@RestController
@RequestMapping("/api/users/me")
public class UserAccountController {

    private final UserAccountDeletionService deletionService;

    public UserAccountController(UserAccountDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @DeleteMapping
    public Result<Void> deleteMe() {
        deletionService.deleteAccount(CurrentUser.requireId());
        return Result.ok(null);
    }
}
