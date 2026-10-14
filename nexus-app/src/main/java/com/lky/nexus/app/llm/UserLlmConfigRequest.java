package com.lky.nexus.app.llm;

import jakarta.validation.constraints.Size;

/**
 * 保存用户自定义对话模型凭据的请求。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code baseUrl} / {@code modelName}：本次提交的值；传空串即清空。</li>
 *   <li>{@code apiKey}：<b>留空 = 保持已存的 key 不变</b>（回显本就不返回明文，
 *       输入框加载为空；用户不改动就不该把旧 key 清掉）；填了才覆盖。</li>
 *   <li>{@code enabled}：是否启用；启用时服务端会校验三项是否齐全。</li>
 * </ul>
 */
public record UserLlmConfigRequest(

        @Size(max = 500, message = "Base URL 长度不能超过 500")
        String baseUrl,

        @Size(max = 500, message = "API Key 长度不能超过 500")
        String apiKey,

        @Size(max = 200, message = "模型名长度不能超过 200")
        String modelName,

        Boolean enabled) {
}
