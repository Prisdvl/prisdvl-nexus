package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 流式提问请求。
 */
public record StreamChatRequest(

        @NotNull(message = "conversationId 不能为空")
        Long conversationId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "单条消息长度不能超过 8000 字符")
        String content) {
}
