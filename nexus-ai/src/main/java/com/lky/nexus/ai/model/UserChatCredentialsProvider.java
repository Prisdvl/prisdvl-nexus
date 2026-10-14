package com.lky.nexus.ai.model;

import java.util.Optional;

/**
 * 用户自定义对话凭据的查询端口。
 *
 * <p>放在 nexus-ai 是刻意的：依赖方向是 {@code nexus-app → nexus-ai}，
 * nexus-ai 不能反向依赖 nexus-app 的数据库实现。因此由 nexus-ai 定义端口，
 * nexus-app 提供读库的实现（{@code DbUserChatCredentialsProvider}），
 * 通过 Spring 注入进 {@code ChatClientFactory}——与 Retriever 端口族同一种解耦。
 *
 * <p>实现必须是<b>可选</b>的：nexus-ai 单独被引用（纯 AI 层单测、将来的 mcp-server）
 * 时没有这个 bean，工厂应退化为只用服务端全局配置。
 */
public interface UserChatCredentialsProvider {

    /**
     * 取某用户启用中的自定义对话凭据。
     *
     * @param userId 当前发起模型调用的用户
     * @return 用户已填并启用的凭据；未配置 / 未启用 / 字段不完整时返回 {@link Optional#empty()}，
     *         调用方据此回落服务端全局模型。
     */
    Optional<UserChatCredentials> find(Long userId);
}
