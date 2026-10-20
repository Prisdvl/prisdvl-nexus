package com.lky.nexus.app.agent;

import com.lky.nexus.app.agent.model.dto.AgentRunResponse;
import com.lky.nexus.app.agent.model.dto.AgentRunStepResponse;
import com.lky.nexus.app.agent.model.dto.StartAgentRunRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 给 {@code POST /api/agent/runs} 补一个自有流式形态（roadmap 待办 #7 第 3 条）。
 *
 * <p>为什么需要它：运行本身早就是异步的（{@code AgentRunService.start} 把执行丢到虚拟线程池，
 * 立刻返回 runId），但客户端过去只能"轮询 {@code GET /api/agent/runs/{runId}} + {@code /steps}",
 * 或者在对话流里借工具拦截点偷看事件。前者是轮询、后者把 Agent 运行绑在聊天链路上，
 * 两者都不是"这次运行自己的流"。
 *
 * <p>这里刻意<b>不</b>伪造增量：运行过程中的事实只有两种——状态机当前值、以及按顺序追加的
 * step 行（{@code ai_agent_run_step}，由 {@code AgentRunTraceRecorder} 在工具调用前后写入）。
 * 所以流里只有：
 * <ul>
 *   <li>{@code meta}：{@code {runId}}，连上先告诉客户端在看哪次运行；</li>
 *   <li>{@code status}：状态机换了值才发（同一状态不重复刷）；</li>
 *   <li>{@code step}：新落库的 step 行，按出现顺序逐条推（用"已发条数"做游标，
 *       不依赖 step 的字段形态）；</li>
 *   <li>{@code approval}：状态进入 {@code WAITING_APPROVAL} 时带上待审批内容，
 *       让客户端知道"在等什么"，决策仍走 {@code POST /api/agent/runs/{runId}/approval}；</li>
 *   <li>{@code done}：终态（{@code DONE} / {@code FAILED} / {@code CANCELLED}）时发一次并结束。</li>
 * </ul>
 *
 * <p>轮询间隔 300ms 是"读库节奏"，不是进度：客户端看到的每一条 step 都是真实落库的行，
 * 没有估算、没有百分比。客户端断开时立即停（{@link #send} 返回 false 即收工），
 * 不再继续读库——SSE 断连不影响运行本身，运行照旧跑完并落库。
 */
@Component
public class AgentRunStreamService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStreamService.class);

    /** 运行最长可等 10 分钟（与审批超时 5 分钟同量级，留出工具执行与多轮迭代的余量）。 */
    static final long EMITTER_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    /** 读库间隔。只决定"多久看一次库"，不参与任何进度计算。 */
    static final long POLL_MILLIS = 300;

    /** 上限 = 3 分钟读一次 × 2000 次，防止忘了收工把线程挂死。 */
    static final int MAX_POLLS = 2000;

    /** 六态状态机里的终态；{@code WAITING_APPROVAL} 不是终态（还在等人）。 */
    static final Set<String> TERMINAL_STATUSES = Set.of("DONE", "FAILED", "CANCELLED");

    private final AgentRunService agentRunService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AgentRunStreamService(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 发起一次运行并把它的状态/步骤推成 SSE。
     *
     * <p>先 {@code start} 再建 emitter：发起失败（额度、Agent 不存在、参数非法）应该是一个
     * 普通的错误响应，而不是"连上了流然后立刻收到一条 error 帧"——后者会让客户端分不清
     * "没跑起来"和"跑起来了但失败了"。
     */
    public SseEmitter stream(Long userId, StartAgentRunRequest request) {
        Long runId = agentRunService.start(userId, request);
        SseEmitter emitter = newEmitter();
        executor.submit(() -> tail(userId, runId, emitter));
        return emitter;
    }

    /** 唯一测试接缝：生产不覆盖，测试注入一个可观察的 SseEmitter。 */
    protected SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    }

    private void tail(Long userId, Long runId, SseEmitter emitter) {
        String lastStatus = null;
        int emittedSteps = 0;
        try {
            if (!send(emitter, "meta", Map.of("runId", String.valueOf(runId)))) {
                return;
            }
            for (int poll = 0; poll < MAX_POLLS; poll++) {
                Thread.sleep(POLL_MILLIS);
                AgentRunResponse run = agentRunService.get(userId, runId);

                if (!run.status().equals(lastStatus)) {
                    lastStatus = run.status();
                    if (!send(emitter, "status", Map.of("runId", String.valueOf(runId), "status", run.status()))) {
                        return;
                    }
                    if ("WAITING_APPROVAL".equals(run.status()) && run.pendingApproval() != null) {
                        if (!send(emitter, "approval", run.pendingApproval())) {
                            return;
                        }
                    }
                }

                List<AgentRunStepResponse> steps = agentRunService.steps(userId, runId);
                // 游标用"已发条数"而不是 step id：step 行按执行顺序追加，形态与排序规则都在
                // AgentRunTraceRecorder 手里，这里不重复解释它的字段。
                for (int i = emittedSteps; i < steps.size(); i++) {
                    if (!send(emitter, "step", steps.get(i))) {
                        return;
                    }
                }
                emittedSteps = Math.max(emittedSteps, steps.size());

                if (TERMINAL_STATUSES.contains(run.status())) {
                    send(emitter, "done", donePayload(runId, run));
                    emitter.complete();
                    return;
                }
            }
            send(emitter, "error", Map.of("runId", String.valueOf(runId), "message", "流式观察超时，运行仍在后台继续，可用 GET 查询"));
            emitter.complete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(interrupted);
        } catch (Exception ex) {
            // 读库失败或客户端消失：不抛给上层（这里是独立线程，抛了只会进默认处理器）
            log.debug("Agent 运行流结束 runId={} 原因={}", runId, ex.toString());
            safeCompleteWithError(emitter, ex);
        }
    }

    private Map<String, Object> donePayload(Long runId, AgentRunResponse run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", String.valueOf(runId));
        payload.put("status", run.status());
        payload.put("answer", run.answer());
        payload.put("errorCode", run.errorCode());
        payload.put("errorMsg", run.errorMsg());
        payload.put("finishedAt", run.finishedAt() == null ? null : run.finishedAt().toString());
        return payload;
    }

    /**
     * 写一帧；客户端已经走了就返回 false（调用方据此收工）。
     *
     * <p>这里不把 {@link IOException} 当错误：SSE 最常见的异常就是对面关页面，
     * 把它记成 ERROR 会让日志里全是"用户关浏览器"。
     */
    private boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Agent 运行流客户端已断开 event={}", name);
            safeCompleteWithError(emitter, ex);
            return false;
        }
    }

    private void safeCompleteWithError(SseEmitter emitter, Exception cause) {
        try {
            emitter.completeWithError(cause);
        } catch (Exception ignored) {
            // 已经结束的 emitter 再 complete 会抛，忽略即可
        }
    }

    /** 供测试与调用方拼装 payload 用（保持字段名与 done 帧一致）。 */
    static Map<String, Object> statusPayload(Long runId, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", String.valueOf(runId));
        payload.put("status", status);
        return payload;
    }
}
