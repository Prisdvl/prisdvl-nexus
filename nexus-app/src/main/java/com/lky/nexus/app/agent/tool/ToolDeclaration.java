package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.ToolCategory;
import com.lky.nexus.ai.agent.ToolPermission;

/**
 * 一条工具登记项（P0 从 {@code ToolCatalog.Declaration} 提出来的顶层类型）。
 *
 * <p>提出来是为了让 {@link ToolProvider} 能声明工具而不用反向依赖 ToolCatalog 的内部结构：
 * 内置工具、测试用的假工具、后续的插件/MCP 工具都实现同一个 SPI，
 * 于是"有哪些工具"这件事不再只有一处硬编码。
 *
 * @param implementation 带 {@code @Tool} 方法的实现对象（框架反射出名字/描述/schema）
 * @param displayName    界面显示名（模型看不到）
 * @param category       分类（只用于界面分组，不参与任何判定）
 * @param permission     默认权限（首次注册时写入 {@code ai_tool_registry}）
 * @param selfReports    该工具是否自己调 {@code AgentContext.record} 报告过程痕迹
 */
public record ToolDeclaration(Object implementation,
                              String displayName,
                              ToolCategory category,
                              ToolPermission permission,
                              boolean selfReports) {
}
