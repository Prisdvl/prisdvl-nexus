-- ============================================================
-- V8 文档关联（知识关联）
--
-- 为什么要把链接落成独立的边表，而不是只存在 kb_document.links 的 JSON 里：
--   ① 反向链接（"谁引用了我"）靠 JSON 字段根本查不了，只能全表扫；
--   ② 关联图（Phase 4 的推荐、Phase 1 的图谱可视化）本质是图查询；
--   ③ 解析阶段已经抽出了 links，落库是顺手的，而错过之后要重跑整条导入。
--
-- 目标解析策略：**不做写入期解析**。WikiLink 的目标文档可能还没导入
-- （先写笔记、后补被引用的那篇很常见），写入期解析会把"暂未解析"永久固化。
-- 因此只存原始 target，查询时再与现有文档匹配（title / 文件名 / 相对路径）。
-- ============================================================

CREATE TABLE kb_document_link (
    id                 BIGINT       NOT NULL,
    kb_id              BIGINT       NOT NULL,
    from_document_id   BIGINT       NOT NULL COMMENT '来源文档',
    link_type          VARCHAR(16)  NOT NULL COMMENT 'WIKILINK / MARKDOWN_LINK / EMBED',
    target             VARCHAR(512) NOT NULL COMMENT '原始目标（笔记名 / 相对路径 / URL）',
    display            VARCHAR(512)          DEFAULT NULL COMMENT '展示文本',
    start_offset       INT          NOT NULL DEFAULT 0 COMMENT '在正文中的位置，供前端定位',
    is_external        TINYINT      NOT NULL DEFAULT 0 COMMENT 'http(s) 外链（不参与内部关联图）',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_link_from (from_document_id),
    KEY idx_link_kb_target (kb_id, target),
    KEY idx_link_type (kb_id, link_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文档关联边';
