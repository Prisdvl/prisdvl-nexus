package com.lky.nexus.app.chat.model;

/**
 * 消息角色，与 Spring AI / OpenAI 的 role 语义一致。
 *
 * <p>{@link #TOOL} 用于存放工具返回结果（Phase 5 起），它<b>不展示给用户</b>，
 * 但必须落库：Agent 的完整推理链依赖它才能回放与审计。
 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}
