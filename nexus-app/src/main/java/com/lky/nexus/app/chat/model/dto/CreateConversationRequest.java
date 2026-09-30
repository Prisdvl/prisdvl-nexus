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
        List<Long> kbIds,

        /**
         * 选用的 Agent（仅 AGENT 模式）；{@code null} = 用内置默认 Agent。
         *
         * <p>注意这里"不填"是<b>合法</b>的（走默认助手），而 RAG 的 {@code kbIds} 不填是错的
         * （没有知识库的 RAG 会话答不出东西）。区别在于 AGENT 模式永远有一个可用的默认 Agent，
         * 而 RAG 没有"默认知识库"这种东西——检索哪个库是用户的资料决定的，猜不得。
         */
        Long agentId) {
}
