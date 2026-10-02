package com.lky.nexus.common.security;

/**
 * 一次模型调用的 token 用量去向（5.6）。
 *
 * <p>为什么是一个"端口"而不是直接调 {@code QuotaService}：记账点在 nexus-ai 的
 * {@code UsageMeteringAdvisor} 里，而配额实现（Redis 键、每日窗口、fail-closed 策略）
 * 在 nexus-app。让 ai 依赖 app 是反向依赖；所以这里只保留一个零 Spring 依赖的接口，
 * 真正的 bean 由 app 层装配（与 {@code ObservabilityRegistry} 同一套做法）。
 *
 * <p><b>实现方不得抛异常</b>：模型已经调用了，账记不上只能认，不能因此让用户的回答失败。
 * 记账失败必须自己消化并留痕（见 {@code ObservabilityRegistry.QUOTA_RECORD_FAILED}）。
 */
@FunctionalInterface
public interface ChatUsageRecorder {

    /** 不做任何事：nexus-ai 在缺少装配（如纯 AI 层的单元测试）时的缺省实现。 */
    ChatUsageRecorder NOOP = (userId, promptTokens, completionTokens) -> {
    };

    /**
     * 记一笔归属到具体用户的用量。
     *
     * @param userId           用户 ID，永不为 null（调用方负责判断）
     * @param promptTokens     输入 token 数，已归一为非负（null 按 0 处理）
     * @param completionTokens 输出 token 数，同上
     */
    void record(Long userId, int promptTokens, int completionTokens);

    /**
     * 一次模型调用没有归属到任何用户。
     *
     * <p>正常路径下这**只可能**是调用方漏传了 {@link UserContextKeys#USER_ID}——
     * 也就是说它是一条缺陷信号，必须计数（{@code quota.usage.unattributed}）而不是静默。
     * 显式的系统调用会带 {@link UserContextKeys#SYSTEM_CALL}，根本不会走到这里。
     *
     * @param model 实际调用的模型名，用于定位是哪个入口漏了
     */
    default void recordUnattributed(String model) {
    }
}
