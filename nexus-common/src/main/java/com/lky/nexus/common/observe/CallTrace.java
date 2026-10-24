package com.lky.nexus.common.observe;

/**
 * 一次模型调用的观测留档（{@code observability_call} 的一行）。
 *
 * <p><b>为什么是一个纯 Java record 而不是 entity</b>：产生方在 nexus-ai
 * （{@code UsageMeteringAdvisor}），落库方在 nexus-app。前者不该知道表结构与 MyBatis，
 * 否则"纯 AI 层单测"会被拖进一个数据源。这个 record 就是两者之间唯一的事实形状。
 *
 * <p>字段刻意<b>全部允许为 null</b>（{@code tokensIn} / {@code tokensOut} /
 * {@code firstTokenMs} / {@code errorCode}）：供应商不回传 usage 时 token 就是没有，
 * 写成 0 会让报表上的"0 token"看起来像一次真实的白嫖，而不是"这次没量"。
 *
 * @param requestId   与响应头 / 日志里的 {@code X-Request-Id} 一致，用于把同一轮对话的
 *                    多轮模型调用（工具循环）聚合成"一次请求"
 * @param userId      归属用户；系统调用（如重排）为 {@code null}，因此不进任何用户的报表
 * @param scene       场景：{@code chat} / {@code rag} / {@code agent} / {@code rerank} …，
 *                    见 {@link ObserveScenes}
 * @param model       供应商返回的模型名（不是角色名：角色会随配置变，模型名才是账单上的那个）
 * @param tokensIn    prompt token 数
 * @param tokensOut   completion token 数
 * @param latencyMs   本次调用耗时（流式从<b>订阅</b>那一刻起算）
 * @param firstTokenMs 首 Token 延迟；非流式调用没有这个概念，为 {@code null}
 * @param success     本次调用是否正常结束
 * @param errorCode   失败时的业务码（与统一返回体同一个号段）
 */
public record CallTrace(
        String requestId,
        Long userId,
        String scene,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        long latencyMs,
        Long firstTokenMs,
        boolean success,
        Integer errorCode) {
}
