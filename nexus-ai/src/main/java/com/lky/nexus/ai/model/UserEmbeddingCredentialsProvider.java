package com.lky.nexus.ai.model;

import java.util.Optional;

/**
 * 用户自定义<b>嵌入</b>凭据的查询端口（BYOK P1 路线 A）。
 *
 * <p>与 {@link UserChatCredentialsProvider} 同一种解耦方式：依赖方向是
 * {@code nexus-app → nexus-knowledge → nexus-ai}，nexus-ai 不能反向依赖数据库实现。
 * 因此由 nexus-ai 定义端口，nexus-app 提供读库实现
 * （{@code DbUserEmbeddingCredentialsProvider}），nexus-knowledge 的
 * {@code KnowledgeEmbeddingResolver} 消费它。
 *
 * <p>实现必须是<b>可选</b>的：nexus-knowledge / nexus-ai 单独被引用
 * （纯单测、将来的 mcp-server）时没有这个 bean，解析方应退化为只用服务端全局配置。
 *
 * <p><b>查询键是"知识库所有者"而不是"当前请求者"</b>：知识库归属唯一用户
 * （跨用户访问一律 404），向量化写入发生在所有者导入文档时、查询向量化发生在
 * 所有者检索时，两处都应以所有者的凭据为准。这样"用户 A 的 Key 只会用于 A 的库"
 * 是结构性成立，而不是靠调用方记得传对 userId。
 */
public interface UserEmbeddingCredentialsProvider {

    /**
     * 取某用户启用中的自定义嵌入凭据。
     *
     * @param userId 知识库所有者
     * @return 已填并启用的凭据；未配置 / 未启用 / 字段不完整时返回 {@link Optional#empty()}，
     *         调用方据此回落服务端全局 {@code embedding.default}
     */
    Optional<UserEmbeddingCredentials> find(Long userId);
}
