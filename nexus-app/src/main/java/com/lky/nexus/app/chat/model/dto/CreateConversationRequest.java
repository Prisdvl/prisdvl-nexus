package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建会话请求。
 *
 * <p>title 可以为空：空则由首轮用户消息自动截断生成（{@code titleAuto=true}）。
 *
 * <p>{@code kbIds} 只在 {@code mode=RAG} 时有意义（Phase 3.4）：它决定这轮对话去检索哪些知识库。
 * <b>可以绑多个</b>（Phase 5.5 起）：检索侧按库并行召回、再融合，
 * 上限与归属校验见 {@code ConversationServiceImpl.resolveKbIds}——最多 10 个，
 * 每个都必须属于当前用户（一个不属于就整批拒绝，不做"跳过越权的那个"）。
 *
 * <p>空列表在<b>建会话时合法</b>（"先建会话、再绑定"是常见操作），
 * 但 RAG 会话在提问时仍然没有绑任何库，会被显式拒绝（{@code ChatStreamServiceImpl}）——
 * 那不是一个"检索不到"的失败，而是这轮对话没有可检索的范围。
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
         * <p>注意这里"不填"是<b>合法</b>的（走默认助手），RAG 建会话时不填 {@code kbIds} 同样
         * 合法（见类注释第 3 条，可以之后再绑）。区别在于 AGENT 模式永远有一个可用的默认 Agent，
         * 而 RAG 没有"默认知识库"这种东西——检索哪个库是用户的资料决定的，猜不得，
         * 所以没绑定就提问会被拒。
         */
        Long agentId) {
}
