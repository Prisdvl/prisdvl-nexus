-- ============================================================
-- V4 知识库与文档
--
-- 设计要点见 docs/database.md §4：
--   文档身份 = (kb_id, source_path)，而不是文件名或 id。
--   理由：同一个 Obsidian Vault 反复导入时，**相对路径是唯一稳定标识**；
--   改名 = 删除 + 新建（由同步器识别），Obsidian 允许的同名不同目录笔记也不会互相覆盖。
-- ============================================================

CREATE TABLE kb_knowledge_base (
    id                BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件（多租户隔离）',
    name              VARCHAR(128) NOT NULL,
    description       VARCHAR(512)          DEFAULT NULL,
    source_type       VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / OBSIDIAN / MIXED',

    -- 嵌入模型在**建库时冻结**（ADR-008）：向量维度变了，已入库的向量就全部失去可比性，
    -- 而且不会报错、只会表现为"检索结果莫名其妙"。因此这里记录当时用的模型与维度。
    embedding_provider VARCHAR(32) NOT NULL,
    embedding_model    VARCHAR(64) NOT NULL,
    embedding_dim      INT         NOT NULL,

    -- 切分参数：建库时确定，重新导入时沿用（保证同一库内切分策略一致）
    chunk_strategy    VARCHAR(32)  NOT NULL DEFAULT 'MARKDOWN_HEADING' COMMENT 'MARKDOWN_HEADING / RECURSIVE',
    chunk_size        INT          NOT NULL DEFAULT 400 COMMENT '目标 chunk token 上限',
    chunk_overlap     INT          NOT NULL DEFAULT 50,

    -- 冗余统计：导入完成后更新，避免列表页对文档/切片表做 count
    doc_count         INT          NOT NULL DEFAULT 0,
    chunk_count       INT          NOT NULL DEFAULT 0,
    total_tokens      BIGINT       NOT NULL DEFAULT 0,

    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_user_name (user_id, name, deleted),
    KEY idx_kb_user (user_id, deleted, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库';

CREATE TABLE kb_document (
    id             BIGINT        NOT NULL,
    kb_id          BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    file_name      VARCHAR(512)  NOT NULL COMMENT 'SpringBoot.md',
    file_ext       VARCHAR(16)   NOT NULL COMMENT 'md / txt / pdf / docx',
    source_type    VARCHAR(16)   NOT NULL COMMENT 'UPLOAD / OBSIDIAN / ZIP',
    -- 注意长度：source_path 参与唯一键 (kb_id, source_path, deleted)。
    -- InnoDB 的索引键上限是 3072 字节，utf8mb4 下每字符最多 4 字节，
    -- 因此 VARCHAR(1024) 会让唯一键超限并导致迁移失败（真实踩坑：
    -- "Specified key was too long; max key length is 3072 bytes"）。
    -- 512 字符足够容纳任何实际 Vault 路径（Windows MAX_PATH 才 260），
    -- 且 512*4 + 8 + 1 = 2057 字节，安全通过。
    source_path    VARCHAR(512)  NOT NULL COMMENT '相对 Vault 根的路径，如 Spring/SpringBoot.md —— 增量同步的身份键',
    folder         VARCHAR(512)           DEFAULT NULL COMMENT '便于按目录过滤',
    title          VARCHAR(512)           DEFAULT NULL COMMENT 'frontmatter.title 或首个 H1',
    file_size      BIGINT        NOT NULL DEFAULT 0,
    file_hash      CHAR(64)      NOT NULL COMMENT 'SHA-256(原始字节)：判断"文件是否变化"',
    content_hash   CHAR(64)               DEFAULT NULL COMMENT 'SHA-256(规范化正文)：判断"是否只改了时间戳"',
    frontmatter    JSON                   DEFAULT NULL COMMENT '完整 frontmatter',
    tags           JSON                   DEFAULT NULL COMMENT '冗余自 kb_document_tag，供快速展示',
    heading_tree   JSON                   DEFAULT NULL COMMENT '标题目录树，供前端大纲',
    doc_created_at DATETIME(3)            DEFAULT NULL COMMENT '源文件创建时间',
    doc_updated_at DATETIME(3)            DEFAULT NULL COMMENT '源文件修改时间',
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/PARSING/CHUNKING/EMBEDDING/INDEXING/READY/FAILED/SKIPPED',
    error_msg      VARCHAR(2000)          DEFAULT NULL,
    chunk_count    INT           NOT NULL DEFAULT 0,
    token_count    INT           NOT NULL DEFAULT 0,
    version        INT           NOT NULL DEFAULT 1 COMMENT '每次内容变更 +1',
    indexed_at     DATETIME(3)            DEFAULT NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 增量同步的身份键：同库同路径只能有一份未删除的文档
    UNIQUE KEY uk_doc_kb_path (kb_id, source_path, deleted),
    KEY idx_doc_kb_status (kb_id, status, deleted),
    KEY idx_doc_kb_folder (kb_id, folder, deleted),
    KEY idx_doc_kb_hash (kb_id, file_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库文档';

CREATE TABLE kb_document_tag (
    id          BIGINT       NOT NULL,
    kb_id       BIGINT       NOT NULL,
    document_id BIGINT       NOT NULL,
    tag         VARCHAR(128) NOT NULL,
    tag_source  VARCHAR(16)  NOT NULL COMMENT 'FRONTMATTER / INLINE / FOLDER',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_tag (document_id, tag),
    KEY idx_tag_kb (kb_id, tag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档标签';
