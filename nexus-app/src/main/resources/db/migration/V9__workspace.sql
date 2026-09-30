-- ============================================================
-- V9 工作区（平台化 Phase 2 第一个增量）
--
-- 目标架构（§十一/§三十七）：User → Workspace → 资源(KB/Agent/Workflow/Tool/Application)。
-- 第一阶段只实现"个人工作区"（注册时自动创建，1:1 用户），TEAM 类型留作架构预留。
-- 只增不改：kb_knowledge_base 用 ADD COLUMN 补 workspace_id（历史数据允许为空，
-- 所有权查询仍走 user_id，Phase 8 才把权限维度切到 workspace）。
-- ============================================================

CREATE TABLE workspace (
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL COMMENT '所有者（第一阶段个人工作区，1:1 用户）',
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512)          DEFAULT NULL,
    type        VARCHAR(16)  NOT NULL DEFAULT 'PERSONAL' COMMENT 'PERSONAL / TEAM（架构预留）',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 每个用户每种类型只允许一个未删除的工作区（个人工作区唯一）
    UNIQUE KEY uk_ws_user_type (user_id, type, deleted),
    KEY idx_ws_user (user_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作区';

ALTER TABLE kb_knowledge_base
    ADD COLUMN workspace_id BIGINT NULL COMMENT '所属工作区（空=历史数据；Phase 8 前所有权仍走 user_id）'
    AFTER user_id;

CREATE INDEX idx_kb_workspace ON kb_knowledge_base (workspace_id, deleted);