package com.lky.nexus.app.plugin.model.dto;

import java.util.Map;

/**
 * 一个插件的真实状态（{@code GET /api/plugins/{code}}）。
 *
 * @param code    目录里的插件 id
 * @param enabled 覆盖后的真实启停状态（没有覆盖行时是目录默认值）
 * @param config  插件配置；没有配置过时是空 Map 而不是 null，前端少一个分支
 */
public record PluginResponse(String code, boolean enabled, Map<String, Object> config) {
}
