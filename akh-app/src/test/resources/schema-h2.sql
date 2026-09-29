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
