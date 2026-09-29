package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改会话请求（局部更新：为 null 的字段不修改）。
 *
 * <p>刻意<b>不允许</b>在这里改 mode：模式切换会影响上下文语义（纯对话的会话突然开检索，
 * 历史回答的引用关系就不一致了）。要换模式请新建会话。
 */
public record UpdateConversationRequest(

        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        Boolean pinned) {
}
