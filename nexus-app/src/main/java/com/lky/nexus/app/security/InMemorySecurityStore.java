package com.lky.nexus.app.security;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内实现的 {@link SecurityStore}：{@code standalone} 与 {@code test} 的默认实现。
 *
 * <p>过期是<b>惰性</b>的：读的时候才判断，不跑定时任务。这样就没有后台线程，
 * 测试里也不会因为"清理线程还没跑"出现时序抖动。
 *
 * <p>惰性过期带来一个必须处理的后果：<b>过期条目会一直占着内存</b>（没人读它就没人清）。
 * 键里带了用户 id 与日期，长期运行会单调增长。所以 {@code increment} 里加了一道
 * 阈值清理（超过 {@value #PURGE_THRESHOLD} 个键才扫一遍），把摊销成本压到可忽略。
 * 这是有意的取舍：换掉定时线程，代价是极少数情况下内存短暂高于实际键数。
 */
public class InMemorySecurityStore implements SecurityStore {

    /** 超过这么多键才触发一次过期清理（摊销）。 */
    private static final int PURGE_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(AtomicLong value, long expiresAtMillis) {

        boolean expired(long now) {
            return expiresAtMillis <= now;
        }

        static Entry of(long value, long expiresAtMillis) {
            return new Entry(new AtomicLong(value), expiresAtMillis);
        }
    }

    @Override
    public long add(String key, long delta, Duration ttl) {
        long now = System.currentTimeMillis();
        if (entries.size() > PURGE_THRESHOLD) {
            purgeExpired(now);
        }
        Entry entry = entries.compute(key, (ignored, existing) -> {
            // 不存在或已过期 → 开一个新窗口（过期时间从现在重新起算）
            if (existing == null || existing.expired(now)) {
                return Entry.of(0L, now + ttl.toMillis());
            }
            return existing;
        });
        return entry.value().addAndGet(delta);
    }

    @Override
    public OptionalLong get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return OptionalLong.empty();
        }
        if (entry.expired(System.currentTimeMillis())) {
            entries.remove(key, entry);
            return OptionalLong.empty();
        }
        return OptionalLong.of(entry.value().get());
    }

    @Override
    public void put(String key, long value, Duration ttl) {
        entries.put(key, Entry.of(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public boolean exists(String key) {
        return get(key).isPresent();
    }

    @Override
    public void delete(String... keys) {
        for (String key : keys) {
            entries.remove(key);
        }
    }

    private void purgeExpired(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }
}
