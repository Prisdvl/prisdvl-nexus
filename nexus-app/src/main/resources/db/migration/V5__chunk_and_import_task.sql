-- ============================================================
-- V5 切片与导入任务
--
-- kb_chunk 是 RAG 的核心数据：它既是「送进 Embedding 的文本」，
-- 也是「引用时回显给用户的原文片段」。因此同时保留：
--   content     —— 参与嵌入/BM25 的文本（含标题路径前缀）
--   raw_content —— 原文片段（用户看到的必须是原文）
--   content_hash—— 既是"内容是否变化"的判断依据，也是 Embedding 缓存键
-- ============================================================

CREATE TABLE kb_chunk (
    id            BIGINT       NOT NULL,
    kb_id         BIGINT       NOT NULL,
    document_id   BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    chunk_index   INT          NOT NULL COMMENT '文档内序号，从 0 开始',
    chunk_type    VARCHAR(16)  NOT NULL COMMENT 'PARAGRAPH / CODE / TABLE / LIST / HEADING',
    content       MEDIUMTEXT   NOT NULL COMMENT '参与嵌入与 BM25 的文本（含标题路径）',
    raw_content   MEDIUMTEXT            DEFAULT NULL COMMENT '原文片段（展示与引用用）',
    content_hash  CHAR(64)     NOT NULL COMMENT 'SHA-256(content)：Embedding 缓存键',
    heading       VARCHAR(512)          DEFAULT NULL,
    heading_path  VARCHAR(1024)         DEFAULT NULL COMMENT 'Spring > SpringBoot > 自动配置',
    heading_level TINYINT               DEFAULT NULL,
    start_offset  INT          NOT NULL DEFAULT 0 COMMENT '在文档正文中的字符区间，供前端定位',
    end_offset    INT          NOT NULL DEFAULT 0,
    token_count   INT          NOT NULL DEFAULT 0,
    char_count    INT          NOT NULL DEFAULT 0,
    vector_id     VARCHAR(64)           DEFAULT NULL COMMENT 'pgvector 行 id，Phase 3 回填',
    vector_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE / FAILED / STALE',
    es_status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_doc_index (document_id, chunk_index),
    KEY idx_chunk_kb_doc (kb_id, document_id),
    KEY idx_chunk_vector (vector_id),
    KEY idx_chunk_hash (content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档切片';

CREATE TABLE kb_import_task (
    id             BIGINT       NOT NULL,
    kb_id          BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    task_type      VARCHAR(16)  NOT NULL COMMENT 'UPLOAD / VAULT_ZIP / RESYNC',
    status         VARCHAR(16)  NOT NULL COMMENT 'QUEUED / RUNNING / PARTIAL / DONE / FAILED / CANCELLED',
    total_files    INT          NOT NULL DEFAULT 0,
    created_count  INT          NOT NULL DEFAULT 0,
    updated_count  INT          NOT NULL DEFAULT 0,
    skipped_count  INT          NOT NULL DEFAULT 0,
    deleted_count  INT          NOT NULL DEFAULT 0,
    failed_count   INT          NOT NULL DEFAULT 0,
    total_chunks   INT          NOT NULL DEFAULT 0,
    total_tokens   BIGINT       NOT NULL DEFAULT 0,
    progress       INT          NOT NULL DEFAULT 0 COMMENT '0-100',
    started_at     DATETIME(3)           DEFAULT NULL,
    finished_at    DATETIME(3)           DEFAULT NULL,
    error_msg      VARCHAR(2000)         DEFAULT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_task_kb (kb_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '导入任务';

CREATE TABLE kb_import_task_item (
    id          BIGINT        NOT NULL,
    task_id     BIGINT        NOT NULL,
    kb_id       BIGINT        NOT NULL,
    source_path VARCHAR(512)  NOT NULL,
    action      VARCHAR(16)   NOT NULL COMMENT 'CREATED / UPDATED / SKIPPED / DELETED / FAILED',
    document_id BIGINT                 DEFAULT NULL,
    old_hash    CHAR(64)               DEFAULT NULL,
    new_hash    CHAR(64)               DEFAULT NULL,
    chunk_count INT           NOT NULL DEFAULT 0,
    duration_ms INT           NOT NULL DEFAULT 0,
    message     VARCHAR(1000)          DEFAULT NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_item_task (task_id, action)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '导入任务明细';
