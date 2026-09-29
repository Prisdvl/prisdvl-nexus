package com.lky.nexus.app.chat.model.dto;

/**
 * 停止生成的响应。
 *
 * <p>{@code cancelled=false} 表示该 requestId 对应的流已经结束（或不存在）——
 * 这不是错误，前端不必提示，属于正常的"抢跑"（用户点了停止，但模型恰好刚生成完）。
 */
public record StopStreamResponse(String requestId, boolean cancelled) {
}
