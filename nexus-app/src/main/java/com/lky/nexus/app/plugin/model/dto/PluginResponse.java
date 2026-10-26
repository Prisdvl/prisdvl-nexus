package com.lky.nexus.app.plugin.model.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 市场里的一条插件：目录字段（服务端 {@code plugin_catalog}）+ 当前用户的安装状态。
 *
 * <p><b>为什么把目录字段一起返回</b>：前端不再自带目录，市场 tab 要画什么完全由这个响应决定。
 * 如果只返回 code，前端就得为每个 code 再查一次目录——一个列表 N+1 次请求。
 *
 * <p><b>{@code enabled} 就是"装没装"</b>：{@code user_plugin.enabled} 是唯一的安装状态，
 * 不存在"装了但停用"这种中间态。所以 {@code PUT /api/plugins/{code}} 传
 * {@code {"enabled":false}} 就等于卸载，再传 true 就是重新装上（配置还在）。
 *
 * <p><b>没有行 = 未安装</b>：目录里有的插件，新用户拿到的是 {@code enabled=false}。
 * 这条语义在 V34 里改过：之前是"没有行 = 启用"（目录在前端时只能这么办），
 * 目录搬到服务端之后，"默认全开"会让新用户一进市场就满屏已安装——那不是真相。
 *
 * @param icon 目录里的图标 URL；为空时前端用首字母占位
 */
public record PluginResponse(String code,
                             String name,
                             String author,
                             String version,
                             String category,
                             String description,
                             String icon,
                             BigDecimal rating,
                             String downloads,
                             List<String> tools,
                             List<String> tags,
                             boolean verified,
                             boolean official,
                             String updated,
                             boolean enabled,
                             Map<String, Object> config) {
}
