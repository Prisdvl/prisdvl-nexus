package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.ToolCategory;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.KnowledgeSearchTool;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置工具来源（P0）。
 *
 * <p>顺序（{@code @Order(0)}）即界面与 {@code tools/list} 的展示顺序：
 * 检索在前、计算在后——这是 4.3 就定下的用户可见顺序，不能因为这次重构漂移。
 *
 * <p>权限都是 {@link ToolPermission#READ}：两者都不改变任何状态（检索只读索引、
 * 计算无副作用），这是现有真实语义，不是"先把闸门关着"的偷懒。
 * WRITE / DANGEROUS 的分支由 {@code ToolPermissionApprovalTest} 里的假工具覆盖。
 */
@Component
@Order(0)
public class BuiltinToolProvider implements ToolProvider {

    private final KnowledgeSearchTool knowledgeSearchTool;
    private final CalculatorTool calculatorTool;

    public BuiltinToolProvider(KnowledgeSearchTool knowledgeSearchTool, CalculatorTool calculatorTool) {
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.calculatorTool = calculatorTool;
    }

    @Override
    public List<ToolDeclaration> declare() {
        return List.of(
                // knowledge_search 自己上报：它的痕迹要带上只有它知道的信息——
                // "5 条结果（跳过 1 个库）"、"已降级为关键词匹配"、"检索不可用：<异常>"。
                new ToolDeclaration(knowledgeSearchTool, "知识库检索", ToolCategory.RETRIEVAL,
                        ToolPermission.READ, true),
                // calculator 不上报：结果从入参和返回值直接看得出，交给执行壳兜底即可。
                new ToolDeclaration(calculatorTool, "计算器", ToolCategory.COMPUTE,
                        ToolPermission.READ, false));
    }
}
