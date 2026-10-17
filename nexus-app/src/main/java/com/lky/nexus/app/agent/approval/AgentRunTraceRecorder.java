package com.lky.nexus.app.agent.approval;

import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.mapper.AgentRunStepMapper;
import com.lky.nexus.app.agent.model.AgentStepType;
import com.lky.nexus.app.agent.model.RunStepStatus;
import com.lky.nexus.app.agent.model.entity.AgentRunStepPO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行轨迹记录器（P0 §五 / §九）。
 *
 * <p>它把"一步运行轨迹怎么落库"收成一处：{@link AgentRunService} 的 THINK/ANSWER 与
 * 工具回调的 TOOL_CALL/OBSERVATION 都走同一个 {@link RunTrace}。
 * 单独提出来还有一个现实理由：运行轨迹的写入是**审批链路的一部分**
 * （拒绝也要留一条 DENIED 的 OBSERVATION），所以它必须能被测试直接拿到、
 * 而不是藏在某个 Service 的私有内部类里——藏在里面就只能靠复制一份来测，那就等于没测。
 *
 * <p>序号用 {@link AtomicInteger}：工具事件来自 boundedElastic，与写 THINK/ANSWER 的线程
 * 不是同一个。{@code (run_id, seq)} 上的唯一键是第二道保险，撞号时宁可报错也不要静默错序。
 */
@Service
public class AgentRunTraceRecorder {

    private final AgentRunStepMapper stepMapper;

    public AgentRunTraceRecorder(AgentRunStepMapper stepMapper) {
        this.stepMapper = stepMapper;
    }

    /** 为一次运行开一条轨迹（同一个对象既是监听器，也是写入点）。 */
    public RunTrace begin(Long runId) {
        return new RunTrace(stepMapper, runId);
    }

    /**
     * 一次运行的轨迹：实现 {@link AgentContext.ToolCallListener}，因此可以直接交给
     * AgentRuntime；同时暴露 {@link #append} 给"非工具"的步骤（开始、回答、等待审批）。
     */
    public static final class RunTrace implements AgentContext.ToolCallListener {

        private final AgentRunStepMapper stepMapper;
        private final Long runId;
        private final AtomicInteger seq = new AtomicInteger();

        RunTrace(AgentRunStepMapper stepMapper, Long runId) {
            this.stepMapper = stepMapper;
            this.runId = runId;
        }

        @Override
        public Long runId() {
            return runId;
        }

        @Override
        public void onCall(String tool, String input) {
            append(AgentStepType.TOOL_CALL, tool, input, null, RunStepStatus.OK);
        }

        @Override
        public void onResult(AgentContext.ToolCallRecord record) {
            append(AgentStepType.OBSERVATION, record.tool(), record.input(), record.resultSummary(),
                    parseStatus(record.status()));
        }

        @Override
        public void onApprovalRequired(AgentContext.ApprovalRequired event) {
            // 审批记录与 WAITING_APPROVAL 已在 ToolApprovalService 里落库（先落库、后发事件）；
            // 这里只补一条阶段标记，让轨迹上能看出"这里停下来等过人"。
            // 注意写的是阶段标记而不是模型推理：THINK 只是"这一步在做什么"
            append(AgentStepType.THINK, event.toolCode(), event.arguments(),
                    "需要人工确认：等待用户对工具 %s 的决策".formatted(event.toolCode()),
                    RunStepStatus.OK);
        }

        /** 写一步轨迹（入参与输出都会脱敏并截断）。 */
        public void append(AgentStepType type, String toolCode, String input, String output, RunStepStatus status) {
            AgentRunStepPO step = new AgentRunStepPO();
            step.setRunId(runId);
            step.setSeq(seq.incrementAndGet());
            step.setStepType(type.name());
            step.setToolCode(toolCode);
            step.setInputDigest(AgentTraceGuards.digest(input));
            step.setOutputDigest(AgentTraceGuards.digest(output));
            step.setStatus(status.name());
            step.setCreatedAt(LocalDateTime.now());
            stepMapper.insert(step);
        }

        private RunStepStatus parseStatus(String raw) {
            if (raw == null) {
                return RunStepStatus.OK;
            }
            try {
                return RunStepStatus.valueOf(raw);
            } catch (IllegalArgumentException ex) {
                return RunStepStatus.OK;
            }
        }
    }

    /** 轨迹里不会出现的东西：模型的隐藏推理。这里显式留一句话，免得以后有人往里写。 */
    static List<String> forbiddenStepTypes() {
        return List.of("REASONING", "CHAIN_OF_THOUGHT");
    }
}
