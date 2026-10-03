package com.lky.nexus.app.chat.model.dto;

import com.lky.nexus.knowledge.rag.RagAnswer;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息视图。
 *
 * <p>Token / 延迟字段在 Phase 1 就落库并返回，Phase 10 的观测看板可以直接复用，
 * 不必再改表结构或接口。
 */
public record ChatMessageResponse(
        Long id,
        String role,
        /**
         * 产出这条助手消息的 Agent id（5.4）；用户消息与非 AGENT 模式为 {@code null}。
         *
         * <p>它回答的是"这条回答是哪个 Agent 跑的"——会话上的 agentId 会随用户改选而变，
         * 只有这里记的才是当时的真相（见 V17 的文件头）。
         */
        Long agentId,
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer latencyMs,
        Integer firstTokenMs,
        String status,
        String errorMsg,
        LocalDateTime createdAt,
        // Agent 模式下的工具调用痕迹（普通对话为空列表）：刷新后仍能看到"检索了什么"
        List<MessageToolCall> toolCalls,
        /**
         * 这条回答引用的资料片段（无检索的对话为空列表）。
         *
         * <p>与 {@code kb_qa_record.citations} 是同一个形状（{@link RagAnswer.RagCitation}），
         * 只是载体不同：知识库问答存留档表，会话消息存 {@code chat_message.metadata}。
         *
         * <p><b>为什么必须持久化</b>：检索事件只在流里发一次，刷新页面就没了——
         * 于是"这条回答是照着什么说的"在历史回放里无从查证，而多知识库下
         * 连"资料来自哪个库"都看不出来。编号 {@code index} 与回答正文里的 {@code [n]} 对齐。
         */
        List<RagAnswer.RagCitation> citations) {
}
