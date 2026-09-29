package com.lky.nexus.app.chat.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单实例内存实现。
 *
 * <p>清理策略：正常结束（完成 / 停止 / 异常 / 客户端断开）时由
 * {@code ChatStreamServiceImpl} 调用 {@link #clear(String)}；
 * 另外在登记时顺手清理过期条目，作为"回调没触发"的兜底（例如进程被强杀前的悬挂流）。
 * 单实例部署下这样就够了；多实例需要换 Redis 实现。
 */
@Component
public class InMemoryStreamCancellationRegistry implements StreamCancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStreamCancellationRegistry.class);

    /** 超过这个时长仍未清理的登记视为悬挂（SSE 超时是 5 分钟）。 */
    private static final long STALE_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(Long userId, AtomicBoolean cancelled, Instant createdAt) {
    }

    @Override
    public void register(String requestId, Long userId) {
        cleanupStaleEntries();
        entries.put(requestId, new Entry(userId, new AtomicBoolean(false), Instant.now()));
    }

    @Override
    public boolean cancel(String requestId, Long userId) {
        Entry entry = entries.get(requestId);
        if (entry == null) {
            return false;
        }
        // 只有发起者能停止自己的流：否则任何登录用户猜到 requestId 就能打断别人的生成
        if (!entry.userId().equals(userId)) {
            log.warn("拒绝越权停止：requestId={}, 发起者={}, 请求者={}", requestId, entry.userId(), userId);
            return false;
        }
        entry.cancelled().set(true);
        return true;
    }

    @Override
    public boolean isCancelled(String requestId) {
        Entry entry = entries.get(requestId);
        return entry != null && entry.cancelled().get();
    }

    @Override
    public void clear(String requestId) {
        entries.remove(requestId);
    }

    private void cleanupStaleEntries() {
        Instant threshold = Instant.now().minusMillis(STALE_MILLIS);
        entries.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(threshold));
    }
}
