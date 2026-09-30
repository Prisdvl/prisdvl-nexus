package com.lky.nexus.app.chat.model.dto;

import java.util.List;

/**
 * 助手回复的落库草稿（Step 1.6 的 AI 链路使用）。
 *
 * <p>为什么用草稿对象而不是给 {@code appendAssistantMessage} 传十个参数：
 * 调用方（AI 编排层）在不同场景下能填的字段不同——流式过程中还没有 Token 用量，
 * 失败时只有 errorMsg。用对象可以让"哪些字段一定有值"由调用方决定，
 * 而不是被迫传一堆 null。
 */
public record AssistantReplyDraft(
        String content,
        String model,
        String provider,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer latencyMs,
        Integer firstTokenMs,
        String finishReason,
        String requestId,
        String status,
        String errorMsg,
        // Agent 模式的工具调用痕迹（非工具场景为 null）：落进 chat_message.metadata
        List<MessageToolCall> toolCalls) {

    /** 全部字段可空的构建器式工厂：先建骨架，边生成边补。 */
    public static AssistantReplyDraftBuilder builder() {
        return new AssistantReplyDraftBuilder();
    }

    /** 极简构建器（避免为此引入 Lombok @Builder 的隐式行为）。 */
    public static final class AssistantReplyDraftBuilder {
        private String content;
        private String model;
        private String provider;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        private Integer latencyMs;
        private Integer firstTokenMs;
        private String finishReason;
        private String requestId;
        private String status;
        private String errorMsg;
        private List<MessageToolCall> toolCalls;

        public AssistantReplyDraftBuilder content(String value) {
            this.content = value;
            return this;
        }

        public AssistantReplyDraftBuilder model(String value) {
            this.model = value;
            return this;
        }

        public AssistantReplyDraftBuilder provider(String value) {
            this.provider = value;
            return this;
        }

        public AssistantReplyDraftBuilder tokens(Integer prompt, Integer completion, Integer total) {
            this.promptTokens = prompt;
            this.completionTokens = completion;
            this.totalTokens = total;
            return this;
        }

        public AssistantReplyDraftBuilder latency(Integer latencyMs, Integer firstTokenMs) {
            this.latencyMs = latencyMs;
            this.firstTokenMs = firstTokenMs;
            return this;
        }

        public AssistantReplyDraftBuilder finishReason(String value) {
            this.finishReason = value;
            return this;
        }

        public AssistantReplyDraftBuilder requestId(String value) {
            this.requestId = value;
            return this;
        }

        public AssistantReplyDraftBuilder status(String value) {
            this.status = value;
            return this;
        }

        public AssistantReplyDraftBuilder errorMsg(String value) {
            this.errorMsg = value;
            return this;
        }

        public AssistantReplyDraftBuilder toolCalls(List<MessageToolCall> value) {
            this.toolCalls = value;
            return this;
        }

        public AssistantReplyDraft build() {
            return new AssistantReplyDraft(content, model, provider, promptTokens, completionTokens,
                    totalTokens, latencyMs, firstTokenMs, finishReason, requestId, status, errorMsg,
                    toolCalls);
        }
    }
}
