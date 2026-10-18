-- ============================================================
-- V25 用户自定义嵌入模型凭据（BYOK 路线 A）
--
-- 背景：嵌入侧原本**全局唯一**——EmbeddingClientFactory.get(role) 没有 userId，
-- 建库时取全局 embedding.default。用户自带 Key 只放开了对话生成（V19），
-- 理由是嵌入维度在建库时被冻结（ADR-008），切维度会让旧向量全部失效。
--
-- 本表放开"同一维度下的端点与 Key"：用户填自己的 OpenAI 兼容端点 + Key +
-- 白名单内的模型（ai.byok.embedding.allowed-models，默认只含 bailian:text-embedding-v4），
-- 维度固定为平台允许值（ai.byok.embedding.required-dimensions，默认 1024）。
--
-- 【安全】api_key 与 user_llm_config.api_key 同一口径：落库前经 CredentialCipher
-- 做 AES-GCM 字段级加密，接口只回掩码。V19 的注释写着"本期不做字段级加密"，
-- 那是当时的实情；P1 凭证安全落地后已补齐（见 docs/security.md §11）。
--
-- 【为什么维度也落库】白名单保证它当前只有一种取值，但知识库冻结值是
-- (provider, model, dim) 三元组，检索期要拿它跟冻结值比对。把"读的时候一定是 1024"
-- 写死在读路径里，将来放开维度时就得到处改；落库后只需改白名单配置。
--
-- H2 以 MODE=MySQL 执行本脚本，因此沿用 MySQL 方言（ENGINE / COMMENT / ON UPDATE）。
-- ============================================================

CREATE TABLE user_embedding_config (
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件（与知识库同一套隔离约定）',
    provider    VARCHAR(64)  DEFAULT NULL COMMENT '供应商标识，如 bailian；与 model_name 一起受白名单约束',
    model_name  VARCHAR(200) DEFAULT NULL COMMENT '模型名，如 text-embedding-v4',
    base_url    VARCHAR(500) DEFAULT NULL COMMENT 'OpenAI 兼容端点（用户自己的网关）',
    api_key     VARCHAR(500) DEFAULT NULL COMMENT '该用户自己的 API Key（AES-GCM 密文，接口回显掩码）',
    dimensions  INT          NOT NULL DEFAULT 1024 COMMENT '声明维度；必须等于平台允许维度（向量列写死 vector(1024)）',
    enabled     TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 该用户的知识库走他自己的嵌入配置；0 = 回落全局角色',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_embedding_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户自定义嵌入模型凭据（BYOK 路线 A）';
