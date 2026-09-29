package com.lky.nexus.app.chat.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话服务（SSE）。
 */
public interface ChatStreamService {

    /**
     * 建立一次流式问答。
     *
     * <p>方法<b>立即返回</b> emitter：会话归属校验、用户消息落库是同步完成的（很快），
     * 模型流式生成则在返回后异步推送到 emitter。
     *
     * @return 交给 Spring MVC 直接返回给客户端的 SseEmitter
     */
    SseEmitter stream(Long userId, Long conversationId, String content);

    /**
     * 请求停止指定流。
     *
     * @return true 表示命中了正在进行的流
     */
    boolean stop(String requestId, Long userId);
}
