-- ============================================================
-- V7 问答记录
--
-- 为什么单独一张表，而不是复用 chat_message：
--   聊天消息（Phase 1）服务于"多轮对话"，问答记录服务于"知识库审计"——
--   需要一次性看到"问了什么、答了什么、依据了哪几段资料、有没有被预算截断"。
--   两者的生命周期与查询方式都不同：历史是整体读取，不会按引用单独查询，
--   因此引用用 JSON 整体存储即可（等真正做多轮 RAG 时，再把引用挂到消息上，
--   届时这张表退化为"问答审计日志"）。
-- ============================================================

CREATE TABLE kb_qa_record (
    id             BIGINT       NOT NULL,
    kb_id          BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    question       VARCHAR(1000) NOT NULL,
    answer         MEDIUMTEXT   NOT NULL,
    citations      MEDIUMTEXT            DEFAULT NULL COMMENT '引用列表 JSON（含编号、文档、标题路径、是否被引用）',
    context_tokens INT          NOT NULL DEFAULT 0,
    truncated      INT          NOT NULL DEFAULT 0 COMMENT '因 Token 预算被丢弃的段数',
    reranked       TINYINT      NOT NULL DEFAULT 0,
    cost_ms        BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_qa_kb_time (kb_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '知识库问答记录';
