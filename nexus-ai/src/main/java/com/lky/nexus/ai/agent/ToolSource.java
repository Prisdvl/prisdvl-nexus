package com.lky.nexus.ai.agent;

/**
 * 工具的来源（§十九）。
 *
 * <p>今天只有 {@link #BUILTIN}：实现活在代码里（就是那几个 {@code @Tool} 方法），
 * 平台能配置的是"这个用户开着没有""这个 Agent 能不能用"。
 * {@link #MCP} 是下一步——外部 MCP server 提供的工具，那时"实现"才第一次真的来自外部。
 *
 * <p><b>刻意没有 {@code parse(String)}</b>（与 {@code AgentStatus} / {@code KnowledgeBaseStatus}
 * 那几个枚举不同，别照抄）：那些枚举的值是<b>从数据库列读出来的</b>，必须能解析；
 * 而本枚举不是——{@code tool} 表没有 {@code source} 列（V13 文件头记了理由），
 * 值只由 {@code ToolCatalog} 在代码里写死。留一个没人调用的解析方法，
 * 只会让后来的人以为存在"从输入/库里解析来源"这条路径。
 */
public enum ToolSource {

    /** 内置：实现是本仓里的 {@code @Tool} 方法，平台只负责启停与绑定。 */
    BUILTIN,

    /** 外部 MCP server 提供的工具（未实现）。 */
    MCP
}
