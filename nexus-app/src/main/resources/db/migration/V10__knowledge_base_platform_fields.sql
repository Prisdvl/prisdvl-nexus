-- ============================================================
-- V10 知识库平台化字段（平台化 Phase 2）
--
-- 目标模型（§十一）：KnowledgeBase 需要 icon / status / 检索参数 三组字段。
--   status             —— 用户可"启用/禁用"知识库（禁用后不再参与检索）；
--   retrieval_top_k / similarity_threshold —— 检索参数按库持久化（§十七），
--                         本次只落库并暴露 API，实际生效在 Phase 3 的 Retriever 抽象。
-- workspace_id 已在 V9 加入，此处不重复。
-- 只增不改：全部 ADD COLUMN 且带默认值，历史数据自动回填为 ENABLED / 5 / 0.7。
--
-- 注意：每条 ADD COLUMN 单独一条 ALTER。测试环境用 H2（MySQL 模式）执行同一批迁移，
-- 而 H2 不接受 MySQL 的 `ADD COLUMN a ..., ADD COLUMN b ...` 逗号连写形式
-- （Flyway 的 primaryFlyway 是显式 @Bean，测试环境下同样会跑真迁移）。
-- ============================================================

ALTER TABLE kb_knowledge_base
    ADD COLUMN icon VARCHAR(512) NULL COMMENT '图标（URL 或 emoji）' AFTER description;

ALTER TABLE kb_knowledge_base
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ENABLED'
        COMMENT 'ENABLED / DISABLED（禁用后不参与检索）' AFTER icon;

ALTER TABLE kb_knowledge_base
    ADD COLUMN retrieval_top_k INT NOT NULL DEFAULT 5
        COMMENT 'RAG 检索 TopK（Phase 3 起生效）' AFTER status;

ALTER TABLE kb_knowledge_base
    ADD COLUMN similarity_threshold DOUBLE NOT NULL DEFAULT 0.7
        COMMENT '相似度阈值（Phase 3 起生效）' AFTER retrieval_top_k;
