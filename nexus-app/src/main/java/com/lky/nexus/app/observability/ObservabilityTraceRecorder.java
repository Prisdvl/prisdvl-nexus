package com.lky.nexus.app.observability;

import com.lky.nexus.app.observability.mapper.ObservabilityCallMapper;
import com.lky.nexus.app.observability.model.entity.ObservabilityCallPO;
import com.lky.nexus.common.observe.CallTrace;
import com.lky.nexus.common.observe.CallTraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@link CallTraceRecorder} 的落地实现：把 {@code UsageMeteringAdvisor} 交来的
 * 一次调用写成 {@code observability_call} 的一行。
 *
 * <p><b>为什么同步 insert、不排队不批处理</b>：本条链路上一次调用本来就要等模型
 * 几秒钟，多一次主键写入（毫秒级）不改变量级；而队列会引入"进程被杀就丢一段明细"
 * 与"重启后顺序错乱"两个新问题。观测数据的价值全在完整，不在吞吐。
 *
 * <p>异常一律吞掉：端口契约（{@link CallTraceRecorder}）要求实现方自己消化。
 * 写入失败只记 WARN 并带上 requestId——留痕能被日志检索到，就不算静默。
 */
@Component
public class ObservabilityTraceRecorder implements CallTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityTraceRecorder.class);

    private final ObservabilityCallMapper callMapper;

    public ObservabilityTraceRecorder(ObservabilityCallMapper callMapper) {
        this.callMapper = callMapper;
    }

    @Override
    public void record(CallTrace trace) {
        if (trace == null) {
            return;
        }
        try {
            ObservabilityCallPO row = new ObservabilityCallPO();
            row.setRequestId(trace.requestId());
            row.setUserId(trace.userId());
            row.setScene(trace.scene());
            row.setModel(trace.model());
            row.setTokensIn(trace.tokensIn());
            row.setTokensOut(trace.tokensOut());
            row.setLatencyMs(trace.latencyMs());
            row.setFirstTokenMs(trace.firstTokenMs());
            row.setSuccess(trace.success() ? 1 : 0);
            row.setErrorCode(trace.errorCode());
            row.setCreatedAt(LocalDateTime.now());
            callMapper.insert(row);
        } catch (RuntimeException ex) {
            log.warn("调用明细落库失败 requestId={} scene={} model={}",
                    trace.requestId(), trace.scene(), trace.model(), ex);
        }
    }
}
