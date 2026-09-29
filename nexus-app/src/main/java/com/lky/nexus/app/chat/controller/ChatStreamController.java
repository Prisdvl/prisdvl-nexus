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
