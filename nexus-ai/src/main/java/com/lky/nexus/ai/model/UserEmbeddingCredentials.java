package com.lky.nexus.ai.model;

/**
 * 某个用户自定义的<b>嵌入</b>模型凭据（BYOK，OpenAI 兼容）。
 *
 * <p>与服务端 {@code ai.providers} 的全局嵌入配置并列：用户在「设置 → 嵌入模型」里
 * 填了自己的端点与 Key 并启用后，<b>他自己的</b>知识库在建库冻结、向量化写入、
 * 查询向量化三条路径上都走他的网关与额度；未配置或未启用时回落全局
 * {@code embedding.default} 角色。
 *
 * <p><b>与对话侧 BYOK 的关键差别（为什么嵌入必须额外受白名单约束）</b>：
 * 对话模型换来换去只影响单次生成的文字，而嵌入模型决定了<b>向量空间</b>——
 * 向量列宽写死 {@code vector(1024)}（{@code db/vector-migration/V1__vector_store.sql}），
 * 且知识库在创建时把 {@code provider / model / dim} 冻结进 {@code kb_knowledge_base}（ADR-008）。
 * 因此这里的 {@code dimensions} 不是"提示"，而是必须与冻结值相等的硬约束：
 * 首期只允许白名单内的 {@code provider:model} 与固定维度（默认 1024），
 * 遇到别的维度直接拒绝——<b>不做静默截断、不补零</b>。
 *
 * @param provider   供应商标识（白名单 key 的前半段），如 {@code bailian}
 * @param model      模型名（白名单 key 的后半段），如 {@code text-embedding-v4}
 * @param baseUrl    OpenAI 兼容端点，如 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}
 * @param apiKey     该用户的 API Key（解密后的明文；只在内存里存在）
 * @param dimensions 声明维度；必须等于平台允许的维度（默认 1024）
 */
public record UserEmbeddingCredentials(
        String provider,
        String model,
        String baseUrl,
        String apiKey,
        Integer dimensions) {

    /**
     * 用于客户端缓存的指纹。
     *
     * <p>含 {@code apiKey}：用户换了 Key（轮换）就应当构建新客户端，而不是复用挂着旧凭据的实例——
     * 这与 {@code ChatClientFactory} 的用户级缓存同一种做法（它的 cacheKey 也含 apiKey）。
     *
     * <p>刻意<b>不</b>把这个字符串写进日志：它含明文 Key。
     */
    public String fingerprint() {
        return provider + "|" + model + "|" + baseUrl + "|" + apiKey + "|" + dimensions;
    }

    /** 模型引用（{@code provider:model}），与全局路径 {@code ModelRef.value()} 同形，用于日志与嵌入缓存键。 */
    public String reference() {
        return provider + ":" + model;
    }
}
