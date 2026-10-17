package com.lky.nexus.app.agent.tool;

import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.approval.AgentTraceGuards;
import com.lky.nexus.app.agent.approval.ToolApprovalService;
import com.lky.nexus.app.agent.approval.ToolInvocationAuditService;
import com.lky.nexus.app.agent.model.ApprovalStatus;
import com.lky.nexus.app.agent.model.RunStepStatus;
import com.lky.nexus.app.agent.model.ToolInvocationStatus;
import com.lky.nexus.common.exception.BusinessException;
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
    /**
     * P0 授权闸门；为 null = 未启用（仅测试与 MCP 直调路径走这个构造）。
     *
     * <p>Agent 装配（{@link AgentToolFactory}）永远传非 null 的实现，因此
     * "模型的每一次工具调用都过统一授权入口"这条不变量由装配点保证。
     */
    private final ToolAuthorizationService authorization;
    private final ToolApprovalService approvalService;
    private final ToolInvocationAuditService auditService;

    public ExecutingToolCallback(ToolCatalog.Registered tool, ObservabilityRegistry observability) {
        this(tool, observability, null, null, null);
    }

    /** Agent 装配用的构造：带权限判定、审批等待与调用审计（P0）。 */
    public ExecutingToolCallback(ToolCatalog.Registered tool,
                                 ObservabilityRegistry observability,
                                 ToolAuthorizationService authorization,
                                 ToolApprovalService approvalService,
                                 ToolInvocationAuditService auditService) {
        this.tool = tool;
        this.observability = observability;
        this.authorization = authorization;
        this.approvalService = approvalService;
        this.auditService = auditService;
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
        GateResult gate = passGate(toolInput, toolContext);
        if (gate.shortCircuit() != null) {
            // 短路（未授权 / 被拒 / 已取消）也必须留一条痕迹：工具自己没跑，
            // 所以它永远不会自报；不在这里补，界面上就是"没有这一步"而不是"这一步没做成"
            String observation = gate.shortCircuit().output();
            String stepStatus = gate.stepStatus() == null
                    ? RunStepStatus.DENIED.name() : gate.stepStatus().name();
            AgentContext.record(toolContext, new AgentContext.ToolCallRecord(
                    tool.code(), toolInput, clip(observation), stepStatus));
            if (gate.invocationId() != null) {
                ToolInvocationStatus invocationStatus = gate.stepStatus() == RunStepStatus.DENIED
                        ? ToolInvocationStatus.DENIED : ToolInvocationStatus.CANCELLED;
                finishAudit(gate.invocationId(), invocationStatus, observation, null);
            }
            return gate.shortCircuit();
        }
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
            finishAudit(gate.invocationId(), ToolInvocationStatus.SUCCEEDED, result, null);
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
            finishAudit(gate.invocationId(), ToolInvocationStatus.FAILED, "执行失败：" + reason, null);
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

    /**
     * P0 授权闸门：<b>执行前的唯一入口</b>。
     *
     * <p>顺序是安全相关的，不能调整：
     * <ol>
     *   <li>取到用户身份（取不到 ⇒ 拒绝，绝不"没有用户就当公开工具"）；</li>
     *   <li>读数据库里的权威权限（未注册/禁用/权限不可识别 ⇒ 拒绝）；</li>
     *   <li>运行已被取消或已结束 ⇒ 拒绝启动新的工具调用；</li>
     *   <li>需要审批 ⇒ 落库 + 发 SSE + 有界等待，批准才继续。</li>
     * </ol>
     *
     * @return 闸门结论；{@code shortCircuit} 非 null 表示"已定论、不要执行工具"
     */
    private GateResult passGate(String toolInput, ToolContext toolContext) {
        if (authorization == null) {
            return GateResult.open();
        }
        Long userId;
        ToolAuthorizationService.Decision decision;
        try {
            userId = AgentContext.requireUserId(toolContext);
            decision = authorization.authorize(userId, tool.code());
        } catch (BusinessException | IllegalStateException ex) {
            // 未注册、已禁用、权限不可识别、没有用户上下文：一律默认拒绝。
            // 注意 failed=false：这不是"工具坏了"，是"我们没有放行"，措辞要能让模型换个方式
            String reason = ex.getMessage();
            log.warn("工具 {} 未通过授权检查，已拒绝执行：{}", tool.code(), reason);
            return GateResult.denied(observationFor("工具 %s 未执行：%s", reason), RunStepStatus.DENIED);
        }

        Long runId = AgentContext.runId(toolContext);
        String blocked = approvalService.executionBlockedReason(runId);
        if (blocked != null) {
            return GateResult.denied(observationFor("工具 %s 未执行：%s", blocked), RunStepStatus.CANCELLED);
        }

        if (!decision.requiresApproval()) {
            Long invocationId = auditService.begin(userId, runId, tool.code(), decision.permission(),
                    AgentTraceGuards.digest(toolInput));
            return GateResult.allowed(invocationId, decision.permission());
        }

        String reason = approvalReason(decision);
        ToolApprovalService.Ticket ticket = approvalService.request(userId, runId, tool.code(),
                decision.permission(), AgentTraceGuards.digest(toolInput), reason,
                AgentContext.listener(toolContext));
        ToolApprovalService.Outcome outcome = approvalService.await(ticket);
        if (!outcome.execute()) {
            RunStepStatus status = stepStatusOf(outcome.status());
            String observation = outcome.observation();
            return GateResult.denied(observation, status, ticket.invocationId());
        }
        log.info("工具 {} 的审批已通过，继续执行本次调用（runId={} approvalId={}）",
                tool.code(), ticket.runId(), ticket.approvalId());
        return GateResult.allowed(ticket.invocationId(), decision.permission());
    }

    private String approvalReason(ToolAuthorizationService.Decision decision) {
        return decision.permission() == ToolPermission.DANGEROUS
                ? "该工具被标记为高风险操作（DANGEROUS），执行前需要你确认。"
                : "该工具会修改数据（WRITE），执行前需要你确认。";
    }

    private RunStepStatus stepStatusOf(ApprovalStatus status) {
        return switch (status) {
            case DENIED -> RunStepStatus.DENIED;
            case CANCELLED, EXPIRED -> RunStepStatus.CANCELLED;
            case APPROVED, PENDING -> RunStepStatus.OK;
        };
    }

    private void finishAudit(Long invocationId, ToolInvocationStatus status, String result, Integer errorCode) {
        if (invocationId != null) {
            auditService.finish(invocationId, status, AgentTraceGuards.digest(result), errorCode, null);
        }
    }

    private String observationFor(String template, String detail) {
        return template.formatted(tool.code(), detail);
    }

    /** 闸门结论：要么放行（带审计行 id），要么一条短路观察结果。 */
    private record GateResult(Long invocationId, ToolPermission permission, Outcome shortCircuit,
                              RunStepStatus stepStatus) {

        static GateResult open() {
            return new GateResult(null, null, null, null);
        }

        static GateResult allowed(Long invocationId, ToolPermission permission) {
            return new GateResult(invocationId, permission, null, null);
        }

        static GateResult denied(String observation, RunStepStatus status) {
            return new GateResult(null, null, new Outcome(observation, false), status);
        }

        static GateResult denied(String observation, RunStepStatus status, Long invocationId) {
            return new GateResult(invocationId, null, new Outcome(observation, false), status);
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
