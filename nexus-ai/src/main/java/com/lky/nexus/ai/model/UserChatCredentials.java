package com.lky.nexus.ai.model;

/**
 * 某个用户自定义的对话模型凭据（OpenAI 兼容）。
 *
 * <p>与服务端 {@code ai.providers} 的全局配置并列：当某个用户在「设置」里填了自己的
 * base-url / api-key / 模型名并启用后，该用户的对话 / RAG / Agent 生成就走<b>他自己的</b>
 * 网关与额度；未配置或未启用时回落服务端环境变量配置。
 *
 * <p><b>为什么只有对话模型、不含嵌入</b>：嵌入维度在建知识库时已冻结（ADR-008），
 * 切嵌入 key/网关不影响已入库向量，但切维度会让旧向量全部失效。本期只放开对话生成，
 * 嵌入仍走服务端全局配置。
 *
 * @param baseUrl  OpenAI 兼容端点，如 {@code https://api.deepseek.com}
 * @param apiKey   该用户的 API Key
 * @param model    模型名，如 {@code deepseek-chat}
 */
public record UserChatCredentials(String baseUrl, String apiKey, String model) {
}
