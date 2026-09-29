package com.lky.nexus.app.chat.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.chat.model.dto.ChatMessageResponse;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息接口。
 *
 * <p>注意：这里只有"查询历史消息"。<b>发送消息（POST /api/chat/messages）与流式对话
 * （POST /api/chat/stream）在 Step 1.6 实现</b>——它们必须经过 ChatMemory 与模型调用，
 * 不是单纯写库，因此不放在本步。
 */
@RestController
@RequestMapping("/api/chat/messages")
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    public ChatMessageController(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    /** 分页查询会话历史消息（时间正序）。 */
    @GetMapping("/{conversationId}")
    public Result<PageResult<ChatMessageResponse>> list(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(chatMessageService.listMessages(
                CurrentUser.requireId(), conversationId, pageNum, pageSize));
    }
}
