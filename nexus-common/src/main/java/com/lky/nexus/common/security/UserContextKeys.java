package com.lky.nexus.common.security;

/**
 * 塞进 Spring AI Advisor 上下文的键名（5.6）。
 *
 * <p>为什么放 nexus-common：写入方（业务层，如 {@code ChatServiceImpl}）与读取方
 * （{@code UsageMeteringAdvisor}，在 nexus-ai）分属两个模块，键名只能落在公共底座。
 * 字面量写两遍的代价是真实的——写错一个字母不会编译失败，只会**静默少计**，
 * 与本项目一路在防的"假旋钮"是同一类问题。
 *
 * <p>为什么走上下文而不是 ThreadLocal：{@code ChatModel.stream()} 真正的调用发生在
 * 订阅线程（{@code boundedElastic}）上，请求线程的 {@code SecurityContext} / ThreadLocal
 * 在那里是空的。而 Advisor 的 {@code ChatClientRequest.context()} 是装配期就被闭包捕获的
 * 普通 Map，跨线程可见——框架自己的 {@code ChatMemory.CONVERSATION_ID} 走的就是这条路。
 */
public final class UserContextKeys {

    /**
     * 这次模型调用为哪个用户而发（{@link Long}）。
     *
     * <p>缺了它就等于这次调用的 token 消耗没人认领——{@code UsageMeteringAdvisor}
     * 会记一条 {@code quota.usage.unattributed} 计数，而不是默默丢掉。
     */
    public static final String USER_ID = "nexus.userId";

    /**
     * 显式标注"这是一次系统调用，本就没有用户"（{@link Boolean#TRUE}）。
     *
     * <p>只有一个真实用途：{@code LlmReranker} 的重排调用。它由系统发起、算系统成本，
     * 不计入任何用户的 token 配额。
     *
     * <p>为什么需要这个标记：没有它，重排会被判成"调用方忘了传 userId"，
     * 于是每次检索都往 {@code quota.usage.unattributed} 上记一笔。那条计数是给
     * "漏传"报警用的，被一个正常的系统调用天天刷，就没人再看得懂它了。
     * <b>宁可显式说"这是系统调用"，也不要让一个告警计数器变成噪音。</b>
     */
    public static final String SYSTEM_CALL = "nexus.systemCall";

    /**
     * 这次模型调用属于哪个请求（{@link String}，与响应头 {@code X-Request-Id} 同源）。
     *
     * <p>用途：把一次对话里的多轮模型调用（工具循环可能调 3 次）聚成"一次请求"。
     * 缺了它，观测页的请求列表就会把一次问答拆成互相没有关系的几行。
     *
     * <p>为什么不能从 MDC 里捞：流式的真正调用在 {@code boundedElastic} 线程上，
     * 那里没有请求线程的 MDC，与 {@link #USER_ID} 是同一个原因。
     */
    public static final String REQUEST_ID = "nexus.requestId";

    /**
     * 这次模型调用属于哪个业务场景（{@link String}，取值见 {@code ObserveScenes}）。
     *
     * <p>场景只能由<b>调用点</b>声明：{@code UsageMeteringAdvisor} 处在一条通用管线上，
     * 它分不清这次是知识库问答还是 Agent 运行。写成字符串常量而不是猜模型名或 URL 路径——
     * 猜出来的维度，报表看着有、排查时对不上。
     */
    public static final String SCENE = "nexus.scene";

    private UserContextKeys() {
    }
}
