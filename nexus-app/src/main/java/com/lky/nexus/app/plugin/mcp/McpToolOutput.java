package com.lky.nexus.app.plugin.mcp;

import tools.jackson.databind.JsonNode;

/**
 * 把 {@code tools/call} 的结果拼成一段文本。
 *
 * <p>MCP 的返回是 {@code {content: [{type: "text", text: "..."}], isError?: boolean}}。
 * 我们要的是一个<b>字符串</b>（模型的工具结果就是文本），所以按顺序拼接 text 片段；
 * 非 text 的内容（图片等）只留一行占位说明，不静默丢掉——丢掉的症状是"工具说做了，
 * 模型什么也没看见"。
 */
final class McpToolOutput {

    private McpToolOutput() {
    }

    static String text(JsonNode result) {
        if (result == null || result.isNull()) {
            return "";
        }
        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            // 有些 server 直接给一段纯文本结果
            JsonNode plain = result.get("text");
            return plain == null ? result.toString() : plain.asString();
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode item : content) {
            String type = item.has("type") ? item.get("type").asString() : "text";
            if (out.length() > 0) {
                out.append('\n');
            }
            if ("text".equals(type)) {
                JsonNode value = item.get("text");
                out.append(value == null || value.isNull() ? "" : value.asString());
            } else {
                out.append("[%s 内容未渲染]".formatted(type));
            }
        }
        if (result.has("isError") && result.get("isError").asBoolean(false) && out.indexOf("失败") < 0) {
            return "工具报告执行失败：" + out;
        }
        return out.toString();
    }
}
