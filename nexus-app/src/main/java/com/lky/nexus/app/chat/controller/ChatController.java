package com.lky.nexus.app.chat.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.chat.model.dto.AskRequest;
import com.lky.nexus.app.chat.model.dto.ChatAnswerResponse;
import com.lky.nexus.app.chat.service.ChatService;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话接口（非流式）。
 *
 * <p>流式版本 {@code POST /api/chat/stream} 在 Step 1.6 实现；
 * 本接口用于简单场景与自动化测试（流式响应不好在单元测试里断言）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public Result<ChatAnswerResponse> ask(@PathVariable Long conversationId,
                                         @Valid @RequestBody AskRequest request) {
        return Result.ok(chatService.ask(CurrentUser.requireId(), conversationId, request.content()));
    }
}
