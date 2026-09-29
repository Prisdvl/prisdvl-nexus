package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建会话请求。
 *
 * <p>title 可以为空：空则由首轮用户消息自动截断生成（{@code titleAuto=true}）。
 */
public record CreateConversationRequest(

        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        /** PLAIN / RAG / AGENT；为空取默认值 PLAIN。 */
        String mode) {
}
