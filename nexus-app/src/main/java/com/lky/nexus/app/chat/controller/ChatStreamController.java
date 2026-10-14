package com.lky.nexus.app.chat.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.chat.model.dto.StopStreamResponse;
import com.lky.nexus.app.chat.model.dto.StreamChatRequest;
import com.lky.nexus.app.chat.service.ChatStreamService;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话接口。
 *
 * <p>{@code POST} + {@code text/event-stream}：不用浏览器原生 {@code EventSource}，
 * 因为它只能发 GET 且无法携带 {@code Authorization} 头。
 * 前端用 {@code fetch} + {@code ReadableStream} 读取，这样 JWT 走正常请求头，
 * 也不需要"把 Token 放进 URL"这种会把凭证写进访问日志的做法。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {

    private final ChatStreamService chatStreamService;

    public ChatStreamController(ChatStreamService chatStreamService) {
        this.chatStreamService = chatStreamService;
    }

    /**
     * 建立一次流式问答，立即返回 {@link SseEmitter}。
     *
     * <p>会话归属校验与用户消息落库在方法内同步完成，模型生成在返回后异步推送。
     *
     * @param request 流式提问请求（会话 id + 内容）
     * @return 交给 Spring MVC 直接返回客户端的 SseEmitter
     * @throws com.lky.nexus.common.exception.BusinessException 会话不存在/越权（8001）、
     *         知识库越权（3001）、Agent 不可用（5008/5009）等
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody StreamChatRequest request) {
        return chatStreamService.stream(CurrentUser.requireId(), request.conversationId(), request.content());
    }

    /** 停止生成。幂等：流已结束时返回 cancelled=false，不算错误。 */
    @PostMapping("/stream/{requestId}/stop")
    public Result<StopStreamResponse> stop(@PathVariable String requestId) {
        boolean cancelled = chatStreamService.stop(requestId, CurrentUser.requireId());
        return Result.ok(new StopStreamResponse(requestId, cancelled));
    }
}
