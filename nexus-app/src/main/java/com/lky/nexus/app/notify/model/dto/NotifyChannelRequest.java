package com.lky.nexus.app.notify.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建 / 更新通道的请求体。
 *
 * <p>{@code secret} 语义：null 表示「不动原密钥」，空串表示「清掉」，其它值表示「换成这个」——
 * 这样前端不必拿到明文也能保留一个已配置的签名密钥。
 */
public record NotifyChannelRequest(
        @NotBlank(message = "通道名称不能为空")
        @Size(max = 100, message = "通道名称最长 100 字")
        String name,

        @NotBlank(message = "通道类型不能为空")
        String type,

        @NotBlank(message = "目标地址不能为空")
        @Size(max = 500, message = "目标地址最长 500 字")
        String target,

        @Size(max = 200, message = "签名密钥最长 200 字")
        String secret,

        List<String> events,

        Boolean enabled
) {
}
