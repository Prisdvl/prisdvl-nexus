package com.lky.nexus.app.agent;

import com.lky.nexus.app.agent.model.dto.AgentRunResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRunStreamService} 的行为约定。
 *
 * <p>三件要守住的事：① 流里只有真实事实（没有估算进度、没有凭空的 step）；
 * ② 终态发一次 {@code done} 就收工；③ 客户端断开立刻停，不在后台空转读库。
 */
class AgentRunStreamServiceTest {

    private static final long RUN_ID = 42L;

    @Test
    @DisplayName("发起后的流：meta → 状态变化 → done，没有 step 就不捏造 step")
    void streamsOnlyRealFacts() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.start(anyLong(), any())).thenReturn(RUN_ID);
        when(runService.get(anyLong(), anyLong())).thenReturn(run("RUNNING"), run("DONE"));
        when(runService.steps(anyLong(), anyLong())).thenReturn(List.of());

        RecordingEmitter emitter = new RecordingEmitter();
        SseEmitter returned = service(runService, emitter).stream(1L, null);

        // 返回的就是那个 emitter：发起阶段是同步的，客户端拿到 200 时运行已经受理
        assertThat(returned).isSameAs(emitter);
        assertThat(emitter.completed.await(5, TimeUnit.SECONDS)).isTrue();

        // 同一状态不重复刷；终态那一帧之后再发 done
        assertThat(emitter.names).containsExactly("meta", "status", "status", "done");
        assertThat(((Map<?, ?>) emitter.payload(1)).get("status")).isEqualTo("RUNNING");
        assertThat(((Map<?, ?>) emitter.payload(3)).get("status")).isEqualTo("DONE");
    }

    @Test
    @DisplayName("客户端断开就收工：不再继续读库")
    void stopsWhenClientDisconnects() throws Exception {
        AgentRunService runService = mock(AgentRunService.class);
        when(runService.start(anyLong(), any())).thenReturn(RUN_ID);
        when(runService.get(anyLong(), anyLong())).thenReturn(run("RUNNING"));
        when(runService.steps(anyLong(), anyLong())).thenReturn(List.of());

        RecordingEmitter emitter = new RecordingEmitter();
        emitter.failAfter = 0; // 连第一帧都写不出去：对端已经没了
        service(runService, emitter).stream(1L, null);

        assertThat(emitter.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.names).isEmpty();
        verify(runService, never()).get(anyLong(), anyLong());
    }

    private static AgentRunStreamService service(AgentRunService runService, RecordingEmitter emitter) {
        return new AgentRunStreamService(runService) {
            @Override
            protected SseEmitter newEmitter() {
                return emitter;
            }
        };
    }

    private static AgentRunResponse run(String status) {
        return new AgentRunResponse(
                String.valueOf(RUN_ID), status, null, null, "问题", "回答",
                null, 1, 8, null, null, null, null, null, null, null);
    }

    /** 只记录"事件名 + 载荷"，并可指定从第几帧开始模拟对端消失。 */
    static final class RecordingEmitter extends SseEmitter {

        final List<String> names = Collections.synchronizedList(new ArrayList<>());
        final List<Object> payloads = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch completed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        /** -1 = 永不断开；n = 第 n+1 帧开始抛 IOException。 */
        int failAfter = -1;
        private int sends;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            String name = null;
            Object payload = null;
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                Object data = entry.getData();
                if (data instanceof String text) {
                    if (text.startsWith("event:")) {
                        int end = text.indexOf('\n');
                        name = end < 0 ? text.substring(6) : text.substring(6, end);
                    }
                } else if (data != null) {
                    payload = data;
                }
            }
            sends++;
            if (failAfter >= 0 && sends > failAfter) {
                throw new IOException("Broken pipe");
            }
            if (name != null) {
                names.add(name);
                payloads.add(payload);
            }
        }

        @Override
        public void complete() {
            completed.countDown();
        }

        @Override
        public void completeWithError(Throwable ex) {
            closed.countDown();
        }

        Object payload(int index) {
            return payloads.get(index);
        }
    }
}
