package com.lky.prisma.app.infra.web;

import org.slf4j.MDC;

/**
 * 请求 ID 上下文。
 *
 * <p>为什么用 MDC（SLF4J 的 Mapped Diagnostic Context）而不是 ThreadLocal 自己存：
 * <ul>
 *   <li>MDC 本身就是 ThreadLocal + 日志框架集成的产物，日志模板里用 {@code %X{requestId}}
 *       即可自动输出，不需要在每个 log 调用里手动拼；</li>
 *   <li>日志格式在 application.yml 一处配置，全项目生效。</li>
 * </ul>
 *
 * <p>注意：Java 21 虚拟线程下 MDC 依然可用（每个虚拟线程有独立的 MDC 快照），
 * 但<b>跨线程传递</b>（如 RabbitMQ 消费、{@code @Async}）需要显式传递 requestId 参数，
 * 不能指望 MDC 自动继承。
 */
public final class RequestIdHolder {

    /** MDC 中的键名，与 application.yml 的日志模板保持一致。 */
    public static final String MDC_KEY = "requestId";

    private RequestIdHolder() {
    }

    public static void set(String requestId) {
        MDC.put(MDC_KEY, requestId);
    }

    /** 当前请求 ID；非请求线程（如定时任务）返回 null。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
