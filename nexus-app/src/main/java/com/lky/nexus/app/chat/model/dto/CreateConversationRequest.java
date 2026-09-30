package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建会话请求。
 *
 * <p>title 可以为空：空则由首轮用户消息自动截断生成（{@code titleAuto=true}）。
 *
 * <p>{@code kbIds} 只在 {@code mode=RAG} 时有意义（Phase 3.4）：它决定这轮对话去检索哪个知识库。
 * 目前检索侧只支持单库（{@code ChunkSearchService} 的签名是单 {@code knowledgeBaseId}），
 * 因此绑多个会被显式拒绝，而不是"取第一个、忽略其余"——后者会让用户以为跨库检索生效了。
 */
public record CreateConversationRequest(

        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        /** PLAIN / RAG / AGENT；为空取默认值 PLAIN。 */
        String mode,

        /** 绑定的知识库 id（仅 RAG 模式）。可为空：允许"先建会话、再绑定"。 */
        List<Long> kbIds) {
}
