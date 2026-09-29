-- H2 测试库表结构（仅测试用，与 MySQL 迁移脚本 docs/database.md 的 sys_user 对齐）
-- 注意：这里刻意不写 MySQL 专有语法（ENGINE / utf8mb4 / ON UPDATE CURRENT_TIMESTAMP），
--       生产迁移脚本见 Step 0.6 的 src/main/resources/db/migration/
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    email         VARCHAR(128),
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(64),
    avatar_url    VARCHAR(512),
    status        TINYINT      NOT NULL DEFAULT 1,
    last_login_at TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0
);

-- ---------- RBAC：角色与用户角色关联 ----------
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    CONSTRAINT uk_role_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    id      BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);

-- 预置角色。用 MERGE 保证幂等：同一个测试 JVM 里多个 Spring 上下文共享这个内存库，
-- 若用普通 INSERT，第二个上下文启动时就会因唯一键冲突而失败。
MERGE INTO sys_role (id, code, name, description) KEY (code)
    VALUES (1, 'USER', '普通用户', '默认角色，注册时自动授予');
MERGE INTO sys_role (id, code, name, description) KEY (code)
    VALUES (2, 'ADMIN', '管理员', '可访问 /api/admin/**');

-- ---------- 会话与消息（Step 1.4）----------
-- 生产 MySQL 迁移脚本中 content 等大文本会用 TEXT，这里用 VARCHAR 以保持 H2 兼容
CREATE TABLE IF NOT EXISTS chat_conversation (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    title              VARCHAR(200),
    title_auto         BOOLEAN      NOT NULL DEFAULT TRUE,
    mode               VARCHAR(16)  NOT NULL DEFAULT 'PLAIN',
    kb_ids             VARCHAR(2000),
    model_role         VARCHAR(64),
    prompt_template_id BIGINT,
    message_count      INT          NOT NULL DEFAULT 0,
    last_message_at    TIMESTAMP,
    pinned             BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_message (
    id                BIGINT       NOT NULL PRIMARY KEY,
    conversation_id   BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    role              VARCHAR(16)  NOT NULL,
    content           VARCHAR(8000),
    reasoning_content VARCHAR(8000),
    model             VARCHAR(128),
    provider          VARCHAR(64),
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    latency_ms        INT,
    first_token_ms    INT,
    finish_reason     VARCHAR(32),
    request_id        VARCHAR(64),
    prompt_version_id BIGINT,
    parent_message_id BIGINT,
    status            VARCHAR(16)  NOT NULL DEFAULT 'DONE',
    error_msg         VARCHAR(2000),
    metadata          VARCHAR(4000),
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- Spring AI 会话记忆表（Step 1.5）----------
-- DDL 抄自 spring-ai-model-chat-memory-repository-jdbc-2.0.1.jar 的 schema-h2.sql。
-- 我们刻意自己建表并把应用侧 initialize-schema 设为 never：
-- 让"框架管 H2 + Flyway 管 MySQL"会导致 H2 测试通过而 MySQL 生产失败（见 V3 迁移脚本注释）。
-- conversation_id 是 VARCHAR(36)：记忆 key 必须放得下（见 ChatMemoryKeys）。
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content         LONGVARCHAR NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    timestamp       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sequence_id     BIGINT      NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, timestamp DESC);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, sequence_id);

-- ---------- 知识库与文档（Phase 2）----------
-- 与 Flyway V4 保持语义一致（H2 无 ENGINE/COLLATE/ON UPDATE，故各自书写）
CREATE TABLE IF NOT EXISTS kb_knowledge_base (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    description        VARCHAR(512),
    source_type        VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    embedding_provider VARCHAR(32)  NOT NULL,
    embedding_model    VARCHAR(64)  NOT NULL,
    embedding_dim      INT          NOT NULL,
    chunk_strategy     VARCHAR(32)  NOT NULL DEFAULT 'MARKDOWN_HEADING',
    chunk_size         INT          NOT NULL DEFAULT 400,
    chunk_overlap      INT          NOT NULL DEFAULT 50,
    doc_count          INT          NOT NULL DEFAULT 0,
    chunk_count        INT          NOT NULL DEFAULT 0,
    total_tokens       BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_kb_user_name UNIQUE (user_id, name, deleted)
);

CREATE TABLE IF NOT EXISTS kb_document (
    id             BIGINT        NOT NULL PRIMARY KEY,
    kb_id          BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    file_name      VARCHAR(512)  NOT NULL,
    file_ext       VARCHAR(16)   NOT NULL,
    source_type    VARCHAR(16)   NOT NULL,
    source_path    VARCHAR(512)  NOT NULL,
    folder         VARCHAR(512),
    title          VARCHAR(512),
    file_size      BIGINT        NOT NULL DEFAULT 0,
    file_hash      VARCHAR(64)   NOT NULL,
    content_hash   VARCHAR(64),
    frontmatter    VARCHAR(8000),
    tags           VARCHAR(4000),
    heading_tree   VARCHAR(8000),
    doc_created_at TIMESTAMP,
    doc_updated_at TIMESTAMP,
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    error_msg      VARCHAR(2000),
    chunk_count    INT           NOT NULL DEFAULT 0,
    token_count    INT           NOT NULL DEFAULT 0,
    version        INT           NOT NULL DEFAULT 1,
    indexed_at     TIMESTAMP,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_doc_kb_path UNIQUE (kb_id, source_path, deleted)
);

CREATE INDEX IF NOT EXISTS idx_doc_kb_status ON kb_document (kb_id, status, deleted);
CREATE INDEX IF NOT EXISTS idx_doc_kb_folder ON kb_document (kb_id, folder, deleted);
CREATE INDEX IF NOT EXISTS idx_doc_kb_hash ON kb_document (kb_id, file_hash);

CREATE TABLE IF NOT EXISTS kb_document_tag (
    id          BIGINT       NOT NULL PRIMARY KEY,
    kb_id       BIGINT       NOT NULL,
    document_id BIGINT       NOT NULL,
    tag         VARCHAR(128) NOT NULL,
    tag_source  VARCHAR(16)  NOT NULL,
    CONSTRAINT uk_doc_tag UNIQUE (document_id, tag)
);

CREATE INDEX IF NOT EXISTS idx_tag_kb ON kb_document_tag (kb_id, tag);
