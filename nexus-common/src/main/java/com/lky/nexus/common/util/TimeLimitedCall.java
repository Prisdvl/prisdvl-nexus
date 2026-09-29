package com.lky.nexus.common.util;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 带超时执行一次外部调用，超时或异常都返回 {@link Optional#empty()}。
 *
 * <p><b>为什么需要它</b>（真实数据）：同一个问答链路，实测耗时从 28 秒涨到 <b>172 秒</b>——
 * 检索部分始终是百毫秒级，时间全耗在两次 LLM 调用上，而且是<b>网关侧退化</b>，
 * 不由本项目的代码控制。更糟的是它不会报错，只是"一直不返回"：
 * 用户干等三分钟，HTTP 连接被占住，也无法给出任何降级结果。
 *
 * <p>因此凡是"外部依赖、有替代路径"的调用，都必须能被超时掐断：
 * <ul>
 *   <li>重排超时 → 保持召回顺序继续回答（重排本来就是增强项）；</li>
 *   <li>后续 Phase 5 的工具调用 / MCP 调用同理。</li>
 * </ul>
 *
 * <p>实现用虚拟线程（Java 21）：等待期间不占平台线程，超时后中断任务。
 * 注意这里是"尽力取消"——外部 HTTP 调用能否真正中断取决于客户端实现，
 * 但至少调用方不会再被无限期阻塞。
 */
public final class TimeLimitedCall {

    private TimeLimitedCall() {
    }

    /**
     * @param timeout  超时时间
     * @param supplier 要执行的外部调用
     * @return 调用结果；超时、被中断或抛异常时为空
     */
    public static <T> Optional<T> run(Duration timeout, Supplier<T> supplier) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return attempt(supplier);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(supplier::get);
            try {
                return Optional.ofNullable(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (Exception ex) {
                future.cancel(true);
                return Optional.empty();
            }
        }
    }

    private static <T> Optional<T> attempt(Supplier<T> supplier) {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
