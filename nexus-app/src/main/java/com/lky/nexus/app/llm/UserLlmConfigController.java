package com.lky.nexus.app.llm;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户的「模型 Key」配置接口。
 *
 * <p>与 {@code UserController} 同约定：<b>本控制器所有接口都没有 userId 参数</b>，
 * 用户身份只从 JWT 的 {@code sub} 取（{@link CurrentUser#requireId()}）——
 * 从请求体接收 userId 是水平越权的典型来源（ADR-018）。
 *
 * <p>API Key 回显一律掩码（见 {@link UserLlmConfigResponse}），永不返回明文；
 * 落库形态是 {@link com.lky.nexus.app.security.CredentialCipher} 的密文——
 * 「接口不返回明文」与「库里不存明文」是两件事，这里两件都做到了。
 */
@RestController
@RequestMapping("/api/users/me/llm-config")
public class UserLlmConfigController {

    private final UserLlmConfigService userLlmConfigService;

    public UserLlmConfigController(UserLlmConfigService userLlmConfigService) {
        this.userLlmConfigService = userLlmConfigService;
    }

    /** 读取当前用户的自定义对话模型配置（apiKey 掩码）。 */
    @GetMapping
    public Result<UserLlmConfigResponse> get() {
        return Result.ok(userLlmConfigService.get(CurrentUser.requireId()));
    }

    /** 保存（创建或更新）当前用户的自定义对话模型配置。 */
    @PutMapping
    public Result<UserLlmConfigResponse> upsert(@Valid @RequestBody UserLlmConfigRequest request) {
        return Result.ok(userLlmConfigService.upsert(CurrentUser.requireId(), request));
    }
}
