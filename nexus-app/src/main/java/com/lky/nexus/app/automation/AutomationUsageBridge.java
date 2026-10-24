package com.lky.nexus.app.automation;

/**
 * 把"这次自动化运行花了多少 token"从模型记账点接回运行记录。
 *
 * <p><b>为什么需要它</b>：token 的唯一记账点是 nexus-ai 的 {@code UsageMeteringAdvisor}，
 * 它把用量交给 {@code ChatUsageRecorder} 端口（app 层实现是 {@code QuotaChatUsageRecorder}）——
 * 那条链路只记账到用户配额，不返回给调用方。而 {@code AgentAnswer} 里没有用量字段，
 * 自动化要往 {@code automation_run} 落 token，只能在记账口上顺手抄一份。
 *
 * <p><b>为什么是 ThreadLocal 而不是改 Advisor 的签名</b>：Advisor 是 ai 层的公共件，
 * 为了一个消费方加出参，会把"配额记账"和"某次业务调用的计量"耦在一起。
 * 这里用"同一条线程上挂一个收集器"的旁路做法，采集不到就是 null（不写 0 假装知道）。
 *
 * <p><b>边界</b>：模型调用必须发生在 {@link #begin()} 与 {@link #finish()} 之内的同一线程。
 * 自动化是"一次运行一条记录、同步执行"，正好满足；将来若改成并行多轮，需要换成显式上下文。
 */
public final class AutomationUsageBridge {

    private static final ThreadLocal<Slot> CURRENT = new ThreadLocal<>();

    private AutomationUsageBridge() {
    }

    /** 开始收集（覆盖同线程上的旧收集器，避免上一次异常退出残留）。 */
    public static void begin() {
        CURRENT.set(new Slot());
    }

    /**
     * 记一笔用量。由 {@link AutomationUsageRecorder} 在委派配额记账前后调用。
     *
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     */
    public static void capture(int promptTokens, int completionTokens) {
        Slot slot = CURRENT.get();
        if (slot == null) {
            return;
        }
        slot.promptTokens += Math.max(promptTokens, 0);
        slot.completionTokens += Math.max(completionTokens, 0);
        slot.captured = true;
    }

    /**
     * 结束收集并摘掉线程上的收集器（必须调用，否则线程复用会串数据）。
     *
     * @return 本次收集到的用量；一次都没采集到时返回 null
     */
    public static Usage finish() {
        Slot slot = CURRENT.get();
        CURRENT.remove();
        if (slot == null || !slot.captured) {
            return null;
        }
        return new Usage(slot.promptTokens, slot.completionTokens);
    }

    /** 丢弃收集器（异常路径用，避免把上一次的残留算到下一次头上）。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 一次运行累计到的用量。
     *
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     */
    public record Usage(long promptTokens, long completionTokens) {

        /** 输入 + 输出。 */
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    private static final class Slot {
        private long promptTokens;
        private long completionTokens;
        private boolean captured;
    }
}
