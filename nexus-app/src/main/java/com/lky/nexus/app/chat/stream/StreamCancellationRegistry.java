package com.lky.nexus.app.chat.stream;

/**
 * 流式生成的"停止"信号登记处。
 *
 * <p>为什么需要它：{@code SseEmitter} 的 {@code complete()} 只是关掉连接，
 * 不会让上游的模型流停下来——如果不主动中断，用户点了"停止生成"之后
 * Token 仍然在消耗（真金白银）。因此需要一个跨请求的标记：
 * 停止接口写标记，流式循环每收到一个分片就读一次标记，命中即中断订阅。
 *
 * <p>为什么抽成接口而不是直接用 Redis：Redis 还没接入（需要 Docker，见 Step 0.4）。
 * 当前是单实例内存实现；等基础设施就绪后加一个 Redis 实现即可支持多实例部署，
 * 业务代码不需要改动。这也是"先接口后实现"的一个实际收益。
 */
public interface StreamCancellationRegistry {

    /** 登记一个正在进行的流（记录发起者，用于鉴权）。 */
    void register(String requestId, Long userId);

    /**
     * 请求停止。
     *
     * @return true 表示确实命中了一个正在进行的流；false 表示它已结束或不存在
     */
    boolean cancel(String requestId, Long userId);

    /** 该流是否已被请求停止。 */
    boolean isCancelled(String requestId);

    /** 流结束后清理，避免内存泄漏。 */
    void clear(String requestId);
}
