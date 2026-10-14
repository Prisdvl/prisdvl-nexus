package com.lky.nexus.app.llm;

/**
 * 用户自定义对话模型凭据的回显。
 *
 * <p>{@code apiKey} 永远<b>不返回明文</b>，只回掩码（如 {@code sk-ab…wZ9}），
 * 另给 {@code hasApiKey} 表示是否已经存过 key——前端据此决定占位文案
 * （"已设置" vs "未设置"），而不是把掩码当真值回显进输入框。
 */
public record UserLlmConfigResponse(
        String baseUrl,
        String modelName,
        boolean enabled,
        boolean hasApiKey,
        String apiKeyMasked) {
}
