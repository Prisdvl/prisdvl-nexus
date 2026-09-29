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
