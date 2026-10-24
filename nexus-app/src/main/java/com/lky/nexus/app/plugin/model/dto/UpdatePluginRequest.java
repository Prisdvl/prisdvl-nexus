package com.lky.nexus.app.plugin.model.dto;

import java.util.Map;

/**
 * 更新插件（{@code PUT /api/plugins/{code}}）。
 *
 * <p><b>null = 不改</b>：只想翻开关的调用方不必把整个 config 再回传一遍
 * （回传是有损的——前端拿到的可能是脱敏后的值）。
 *
 * @param enabled 新的启停状态；null = 保持原值
 * @param config  新的配置对象；null = 保持原值，空 Map = 清空
 */
public record UpdatePluginRequest(Boolean enabled, Map<String, Object> config) {
}
