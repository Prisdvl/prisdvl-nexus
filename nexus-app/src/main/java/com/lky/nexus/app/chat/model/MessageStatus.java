package com.lky.nexus.app.chat.model;

/**
 * 消息状态。
 *
 * <p>{@link #STREAMING} 是流式对话的关键设计：SSE 开始时就落库一条 STREAMING 记录，
 * 边生成边更新；这样即使进程崩溃，用户也能看到"有一条消息没生成完"，
 * 而不是消息凭空消失（对比：等生成完再落库，崩溃就全丢了）。
 */
public enum MessageStatus {
    STREAMING,
    DONE,
    /** 用户点了"停止生成"：保留已生成的内容。 */
    STOPPED,
    FAILED
}
