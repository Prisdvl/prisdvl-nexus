package com.lky.nexus.app.llm;

import java.util.List;

/**
 * 用户自定义嵌入模型凭据的回显。
 *
 * <p>{@code apiKey} 永远<b>不返回明文</b>，只回掩码（如 {@code sk-ab…wZ9}），
 * 另给 {@code hasApiKey} 表示是否已经存过 key——前端据此决定占位文案
 * （"已设置" vs "未设置"），而不是把掩码当真值回显进输入框。
 *
 * <p>后两个字段是<b>平台约束</b>而不是用户数据：白名单与允许的维度都来自
 * {@code ai.byok.embedding.*}。放进同一个响应是为了让前端一次请求就能渲染出
 * 合法的选择项——否则前端得自己硬编码一份白名单，那必然与后端漂移。
 */
public record UserEmbeddingConfigResponse(
        String provider,
        String modelName,
        String baseUrl,
        Integer dimensions,
        boolean enabled,
        boolean hasApiKey,
        String apiKeyMasked,
        /** 平台允许的嵌入模型（{@code provider:model}）。 */
        List<String> allowedModels,
        /** 平台允许的嵌入维度。 */
        int requiredDimensions) {
}
