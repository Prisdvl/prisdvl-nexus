package com.lky.nexus.app.plugin.model.dto;

/**
 * 外部 MCP server 声明的一个工具（{@code tools/list} 的一项）。
 *
 * <p>{@code inputSchema} 是原始 JSON 文本，不做解析：它要被原样交给模型，
 * 解了再拼一遍等于多一处可能改坏 schema 的地方（{@code ToolCatalog} 对内置工具
 * 也是直接透传反射结果，两边保持同一态度）。
 *
 * @param name        工具名（server 内部的短名，注入时会加 {@code mcp__<slug>__} 前缀）
 * @param description 给模型看的描述；为空时用 {@code name} 兜底
 * @param inputSchema JSON Schema 文本
 */
public record McpToolInfo(String name, String description, String inputSchema) {
}
