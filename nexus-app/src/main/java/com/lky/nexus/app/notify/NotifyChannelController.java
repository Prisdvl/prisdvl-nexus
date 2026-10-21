package com.lky.nexus.app.notify;

import com.lky.nexus.app.notify.model.dto.NotifyChannelRequest;
import com.lky.nexus.app.notify.model.dto.NotifyChannelResponse;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知通道接口（{@code /api/users/me/notify-channels}）。
 *
 * <p>路径挂在 {@code /users/me} 下：通道天然属于当前登录用户，不接受 userId 参数，
 * 也就不存在"传别人的 id"的入口。
 */
@RestController
@RequestMapping("/api/users/me/notify-channels")
public class NotifyChannelController {

    private final NotifyService notifyService;

    public NotifyChannelController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @GetMapping
    public Result<List<NotifyChannelResponse>> list() {
        return Result.ok(notifyService.list(CurrentUser.requireId()));
    }

    @PostMapping
    public Result<NotifyChannelResponse> create(@Valid @RequestBody NotifyChannelRequest request) {
        return Result.ok(notifyService.create(CurrentUser.requireId(), request));
    }

    @PutMapping("/{id}")
    public Result<NotifyChannelResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody NotifyChannelRequest request) {
        return Result.ok(notifyService.update(CurrentUser.requireId(), id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        notifyService.delete(CurrentUser.requireId(), id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/test")
    public Result<NotifyChannelResponse> test(@PathVariable Long id) {
        return Result.ok(notifyService.test(CurrentUser.requireId(), id));
    }
}
