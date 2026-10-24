package com.lky.nexus.app.plugin.mcp;

import com.lky.nexus.app.plugin.model.dto.McpToolInfo;

import java.util.List;

/**
 * 一个外部 MCP server 的连接（MCP Client，最小闭环）。
 *
 * <p>只覆盖两个能力：{@code tools/list} 与 {@code tools/call}。
 * 这是"把外部工具装进 Agent 工具表"真正需要的全部；其余能力（resources / prompts /
 * sampling）在有人真的用之前不写——写了的代价是每个 server 都要多测一遍协议。
 *
 * <p>生命周期是一次调用一次：{@link #close()} 之后不可再用。probe 与每次工具调用都会
 * 新建一个实例，不为"省一次握手"引入连接池与它的失效问题。
 */
public interface McpClient extends AutoCloseable {

    /** {@code tools/list}；永不返回 null。 */
    List<McpToolInfo> listTools();

    /**
     * {@code tools/call}。
     *
     * @param toolName      server 内部的工具短名（<b>不带</b> {@code mcp__} 前缀）
     * @param argumentsJson 参数对象 JSON 文本；空串按 {@code {}} 处理
     * @return 工具输出文本（把 MCP 的 content 数组拼成一段可读文本）
     */
    String callTool(String toolName, String argumentsJson);

    /** 释放连接（stdio 会杀掉子进程）。 */
    @Override
    void close();
}
