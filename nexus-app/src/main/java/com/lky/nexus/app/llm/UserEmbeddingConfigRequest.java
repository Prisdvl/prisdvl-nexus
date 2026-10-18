package com.lky.nexus.app.llm;

import com.lky.nexus.app.security.CredentialCipher;
import jakarta.validation.constraints.Size;

/**
 * 保存用户自定义<b>嵌入</b>模型凭据的请求。
 *
 * <p>字段语义与 {@link UserLlmConfigRequest} 保持一致（同一种"留空 = 保留旧值"的约定），
 * 另加嵌入独有的两项约束：
 * <ul>
 *   <li>{@code provider} / {@code modelName}：必须成对出现，且
 *       {@code provider:model} 要在 {@code ai.byok.embedding.allowed-models} 白名单内——
 *       嵌入模型决定向量空间，不能像对话模型那样随便填（见 {@code EmbeddingByokPolicy}）。</li>
 *   <li>{@code dimensions}：<b>可选</b>，省略即取平台允许的维度（默认 1024）。
 *       显式传了就按传入值校验：不等于平台允许维度 → 400/1001。
 *       刻意不"静默忽略错误的维度"——那会让用户以为自己配上了 768 维。</li>
 * </ul>
 */
public record UserEmbeddingConfigRequest(

        @Size(max = 64, message = "provider 长度不能超过 64")
        String provider,

        @Size(max = 200, message = "模型名长度不能超过 200")
        String modelName,

        @Size(max = 500, message = "Base URL 长度不能超过 500")
        String baseUrl,

        @Size(max = CredentialCipher.MAX_PLAINTEXT_LENGTH,
                message = "API Key 长度不能超过 320（落库前会做字段级加密，密文须落在 500 字符列内）")
        String apiKey,

        /** 声明维度；省略表示用平台允许的维度。 */
        Integer dimensions,

        Boolean enabled) {
}
