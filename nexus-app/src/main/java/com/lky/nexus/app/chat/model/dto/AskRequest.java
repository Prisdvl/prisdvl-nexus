package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提问请求（非流式）。
 */
public record AskRequest(

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "单条消息长度不能超过 8000 字符")
        String content) {
}
