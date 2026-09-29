package com.lky.nexus.app.chat.controller;

import com.lky.nexus.app.chat.model.dto.ConversationResponse;
import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.model.dto.UpdateConversationRequest;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话接口。
 *
 * <p>所有接口都从 JWT 取 userId（{@link CurrentUser}），<b>不接受任何形式的 userId 参数</b>——
 * 这是防水平越权最根本的一条。
 */
@RestController
@RequestMapping("/api/chat/conversations")
public class ChatConversationController {

    private final ConversationService conversationService;

    public ChatConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public Result<ConversationResponse> create(@Valid @RequestBody CreateConversationRequest request) {
        return Result.ok(conversationService.create(CurrentUser.requireId(), request));
    }

    @GetMapping
    public Result<PageResult<ConversationResponse>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(conversationService.list(CurrentUser.requireId(), pageNum, pageSize));
    }

    @GetMapping("/{conversationId}")
    public Result<ConversationResponse> detail(@PathVariable Long conversationId) {
        return Result.ok(conversationService.detail(CurrentUser.requireId(), conversationId));
    }

    @PutMapping("/{conversationId}")
    public Result<ConversationResponse> update(@PathVariable Long conversationId,
                                              @Valid @RequestBody UpdateConversationRequest request) {
        return Result.ok(conversationService.update(CurrentUser.requireId(), conversationId, request));
    }

    @DeleteMapping("/{conversationId}")
    public Result<Void> delete(@PathVariable Long conversationId) {
        conversationService.delete(CurrentUser.requireId(), conversationId);
        return Result.ok();
    }
}
