package com.lky.nexus.app.agent.tool;

import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 工具执行壳（4.3，§十九 的 ToolExecutor）：包在原始回调外面，做四件与工具实现无关的事——
 * <b>计数、留日志、把失败变成一句明确的观察结果回灌给模型、兜底留痕</b>。
 *
 * <p>P1 的权限检查 / 人工审批 / 超时 / 限流都插在这一层（那时它会变成真正的"执行器"），
 * 而工具实现（{@code @Tool} 方法）一行都不用改——这正是把壳独立的理由。
 *
 * <p><b>第四件（兜底留痕）是 4.3 真机上补的</b>，见 decisions.md B.40。原先过程痕迹由<b>工具自己</b>
 * 调 {@code AgentContext.record} 上报，于是"某个工具忘了上报"的表现是：它<b>执行成功</b>，
 * 而日志写着"工具调用 0 次"、{@code AgentAnswer.usedTools} 是 false、流式界面上一张卡片都没有。
 * 多工具之前这条永远不会露头——当时唯一那个工具自己报，计数不可能出错。
 * 现在壳会在工具没自报时补一条通用痕迹（见 {@link #execute}）。
 *
 * <p><b>为什么失败了要"回灌"而不是往外抛</b>：工具抛异常往外走，整轮对话就断在这里，
 * 用户看到的是"回答失败"；而模型拿到"工具失败：除以零没有定义"之后，完全可能改用别的算法
 * 或者如实告诉用户。§六十七 要的"可降级"就落在这一句上。
 * （框架自己也会把异常消息当工具结果回填——这一点在 {@code AgentContext} 的注释里记着实测经过。
 * 这里再包一层不是为了替它做，而是为了措辞统一、计数可查，并且让这个行为不依赖框架内部实现。）
 *
 * <p><b>必须同时实现两个 call 重载</b>——这是一处实测过的坑：
 * {@link ToolCallback#call(String, ToolContext)} 在接口里的默认实现只打一行日志，
 * 然后转调 {@link ToolCallback#call(String)}（{@code javap -c} 读出来的），
 * 而 {@code MethodToolCallback} 恰恰是靠这个重载把 {@link ToolContext} 注入进工具方法的。
 * 只实现无参那个重载，工具就再也读不到"当前用户"——症状是"Agent 突然不检索了，一条报错都没有"。
 */
public class ExecutingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ExecutingToolCallback.class);

    private final ToolCatalog.Registered tool;
    private final ObservabilityRegistry observability;

    public ExecutingToolCallback(ToolCatalog.Registered tool, ObservabilityRegistry observability) {
        this.tool = tool;
        this.observability = observability;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // 名字/描述/schema 一律透传实现（单一来源，不在这里做任何改写）
        return tool.callback().getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return tool.callback().getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return execute(toolInput, null).output();
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return execute(toolInput, toolContext).output();
    }

    /**
     * 把结果与"这次是不是失败了"一起交出来的入口，给<b>不经模型</b>的调用方（MCP 的
     * {@code tools/call}）用。
     *
     * <p>为什么需要它：模型那条链路只需要一句能读懂的话（失败也当观察结果回灌），
     * 但 MCP 的响应里有 {@code isError} 这个字段——把失败也报成 {@code isError: false}
     * 就是对外说了假话。与其让 MCP 自己去猜这句话是不是失败，不如壳把判定结果一起给出。
     */
    public Outcome invoke(String toolInput, ToolContext toolContext) {
        return execute(toolInput, toolContext);
    }

    /** 一次工具执行的结果：{@code output} 是回给调用方看的文本，{@code failed} 是它是否来自失败。 */
    public record Outcome(String output, boolean failed) {
    }

    private Outcome execute(String toolInput, ToolContext toolContext) {
        // 兜底只在工具<b>不自报</b>时启用：自报的工具（今天只有 knowledge_search）会写上
        // 只有它自己知道的细节，壳再补一条就会变成同一件事的两张卡片（B.40）。
        // 没有监听器时 AgentContext.call/record 本身是空操作，所以 MCP 直调那条路不受影响。
        boolean backstop = !tool.selfReports();
        if (backstop) {
            AgentContext.call(toolContext, tool.code(), toolInput);
        }
        long start = System.currentTimeMillis();
        try {
            String result = toolContext == null
                    ? tool.callback().call(toolInput)
                    : tool.callback().call(toolInput, toolContext);
            observability.increment(ObservabilityRegistry.TOOL_CALL_SUCCEEDED);
            log.debug("工具 {} 执行成功，耗时 {} ms", tool.code(), System.currentTimeMillis() - start);
            if (backstop) {
                // 通用痕迹里 input 用模型给的原始 JSON（壳不去解析它——解析等于又一份 schema）；
                // 摘要直接用返回值：兜底要的是"这一步做了什么、成了没有"确凿可信，
                // 而不是替工具讲一句漂亮的转述
                AgentContext.record(toolContext,
                        new AgentContext.ToolCallRecord(tool.code(), toolInput, clip(result)));
            }
            return new Outcome(result, false);
        } catch (Exception ex) {
            observability.increment(ObservabilityRegistry.TOOL_CALL_FAILED);
            String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName() : ex.getMessage();
            // warn 而不是 error：这不是系统故障，是"这一次调用没成功"，而且已经被如实回灌
            log.warn("工具 {} 执行失败（{} ms）：{}", tool.code(), System.currentTimeMillis() - start, reason);
            if (backstop) {
                // 失败同样要留痕：否则界面上是"没有这一步"，而不是"这一步没做成"
                AgentContext.record(toolContext,
                        new AgentContext.ToolCallRecord(tool.code(), toolInput, "执行失败：" + reason));
            }
            return new Outcome("工具 %s 执行失败：%s。请换一种方式完成这一步，或如实告知用户这一步没有做成。"
                    .formatted(tool.code(), reason), true);
        }
    }

    /** 痕迹摘要的上限：卡片是给用户扫一眼的，不是把工具返回值再贴一遍。 */
    private static final int SUMMARY_LIMIT = 120;

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= SUMMARY_LIMIT ? flat : flat.substring(0, SUMMARY_LIMIT) + "…";
    }
}
