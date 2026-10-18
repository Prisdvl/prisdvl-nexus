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
 * 当前用户的「嵌入模型」配置接口（BYOK 路线 A）。
 *
 * <p>与 {@code UserLlmConfigController} 同约定：<b>本控制器所有接口都没有 userId 参数</b>，
 * 用户身份只从 JWT 的 {@code sub} 取（{@link CurrentUser#requireId()}）——
 * 从请求体接收 userId 是水平越权的典型来源（ADR-018）。
 *
 * <p>API Key 回显一律掩码，落库形态是 {@link com.lky.nexus.app.security.CredentialCipher} 的密文。
 * 白名单与允许的维度由 {@code ai.byok.embedding.*} 决定，随响应一起下发，
 * 前端不硬编码一份（否则必然与后端漂移）。
 */
@RestController
@RequestMapping("/api/users/me/embedding-config")
public class UserEmbeddingConfigController {

    private final UserEmbeddingConfigService userEmbeddingConfigService;

    public UserEmbeddingConfigController(UserEmbeddingConfigService userEmbeddingConfigService) {
        this.userEmbeddingConfigService = userEmbeddingConfigService;
    }

    /** 读取当前用户的自定义嵌入模型配置（apiKey 掩码；未配置时也返回白名单）。 */
    @GetMapping
    public Result<UserEmbeddingConfigResponse> get() {
        return Result.ok(userEmbeddingConfigService.get(CurrentUser.requireId()));
    }

    /** 保存（创建或更新）当前用户的自定义嵌入模型配置。 */
    @PutMapping
    public Result<UserEmbeddingConfigResponse> upsert(@Valid @RequestBody UserEmbeddingConfigRequest request) {
        return Result.ok(userEmbeddingConfigService.upsert(CurrentUser.requireId(), request));
    }
}
