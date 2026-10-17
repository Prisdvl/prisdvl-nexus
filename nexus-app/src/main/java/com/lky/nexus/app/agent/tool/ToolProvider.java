package com.lky.nexus.app.agent.tool;

import java.util.List;

/**
 * 工具来源 SPI（P0，§六）。
 *
 * <p>它存在之前，"有哪些工具"只有一处答案：{@code ToolCatalog} 构造函数里写死的两条登记项。
 * 那对两个内置工具够用，但它把三件本该分开的事绑死了——工具从哪来、风险等级是多少、
 * 执行前要不要审批——于是测试里想放一个 WRITE 工具来验审批，都没有地方可插入。
 *
 * <p>现在 {@code ToolCatalog} 注入 {@code List<ToolProvider>} 并保持 Spring 的 bean 顺序：
 * <ul>
 *   <li>{@link BuiltinToolProvider} —— 内置工具（knowledge_search / calculator）；</li>
 *   <li>测试的 {@code @TestConfiguration} —— 假工具（用于验证 WRITE / DANGEROUS 拦截）；</li>
 *   <li>后续的插件 / MCP provider —— 外部工具。</li>
 * </ul>
 *
 * <p><b>无论来自哪个 provider，执行都走同一个 {@code ExecutingToolCallback}</b>：
 * SPI 只解决"工具从哪注册进来"，不解决"能不能执行"——后者一律由权限与审批决定。
 */
public interface ToolProvider {

    /** 本来源登记的工具（顺序即装配与展示顺序）。 */
    List<ToolDeclaration> declare();
}
