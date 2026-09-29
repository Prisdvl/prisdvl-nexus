-- ============================================================
-- V2 会话与消息
--
-- 设计要点见 docs/database.md §5：
--   chat_conversation = 会话（含冗余计数与最后消息时间，避免列表页对消息表做 count）
--   chat_message      = 完整聊天历史（Chat History），与 Spring AI 的 ChatMemory 职责分离（ADR-011）
-- Token / 延迟字段现在就落库，Phase 10 的观测看板直接复用，不必再改表。
-- ============================================================

CREATE TABLE chat_conversation (
    id                 BIGINT       NOT NULL,
    user_id            BIGINT       NOT NULL COMMENT '归属用户：所有查询必须带此条件（多租户隔离）',
    title              VARCHAR(200)          DEFAULT NULL,
    title_auto         TINYINT      NOT NULL DEFAULT 1 COMMENT '标题是否自动生成；用户改过之后置 0',
    mode               VARCHAR(16)  NOT NULL DEFAULT 'PLAIN' COMMENT 'PLAIN / RAG / AGENT',
    kb_ids             JSON                  DEFAULT NULL COMMENT '绑定的知识库 id 列表，Phase 3 启用',
    model_role         VARCHAR(64)           DEFAULT NULL COMMENT '使用的模型逻辑角色，如 chat.default',
    prompt_template_id BIGINT                DEFAULT NULL COMMENT 'Phase 9 起绑定提示词版本',
    message_count      INT          NOT NULL DEFAULT 0,
    last_message_at    DATETIME(3)           DEFAULT NULL,
    pinned             TINYINT      NOT NULL DEFAULT 0,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conv_user (user_id, deleted, pinned, last_message_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '会话';

CREATE TABLE chat_message (
    id                BIGINT       NOT NULL,
    conversation_id   BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    role              VARCHAR(16)  NOT NULL COMMENT 'USER / ASSISTANT / SYSTEM / TOOL',
    content           MEDIUMTEXT            DEFAULT NULL,
    reasoning_content MEDIUMTEXT            DEFAULT NULL COMMENT '推理型模型的思考内容，不展示但保留',
    model             VARCHAR(128)          DEFAULT NULL,
    provider          VARCHAR(64)           DEFAULT NULL,
    prompt_tokens     INT                   DEFAULT NULL,
    completion_tokens INT                   DEFAULT NULL,
    total_tokens      INT                   DEFAULT NULL,
    latency_ms        INT                   DEFAULT NULL,
    first_token_ms    INT                   DEFAULT NULL COMMENT '首 Token 延迟：流式体验的核心指标',
    finish_reason     VARCHAR(32)           DEFAULT NULL,
    request_id        VARCHAR(64)           DEFAULT NULL COMMENT '与响应头 X-Request-Id 一致，用于串联日志',
    prompt_version_id BIGINT                DEFAULT NULL COMMENT 'Phase 9：本次调用使用的提示词版本',
    parent_message_id BIGINT                DEFAULT NULL COMMENT '重新生成时指向被重生成的消息',
    status            VARCHAR(16)  NOT NULL DEFAULT 'DONE' COMMENT 'STREAMING / DONE / STOPPED / FAILED',
    error_msg         VARCHAR(2000)         DEFAULT NULL,
    metadata          JSON                  DEFAULT NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 查询模式固定为"某会话的消息按 id 升序"（雪花 id 天然时间有序），因此走这条联合索引
    KEY idx_msg_conv (conversation_id, user_id, id),
    KEY idx_msg_request (request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '聊天消息';
