-- ============================================================
-- 嵌入缓存主键改为 (content_hash, model)
--
-- 背景：V1 以 content_hash 为唯一主键，model 只是旁注列。
-- 从 ollama:bge-m3 切到 bailian:text-embedding-v4 后，相同文本会
-- 命中旧模型向量；写入侧却按新模型语义空间检索，相似度被阈值滤光，
-- 表现为 vector / hybrid 永远空。
--
-- 复合主键后：同一段文本可为不同模型并存两份向量，互不覆盖、互不误命中。
-- 存量行已有 model 列（NOT NULL），直接换主键即可，无需回填。
-- ============================================================

ALTER TABLE embedding_cache DROP CONSTRAINT embedding_cache_pkey;

ALTER TABLE embedding_cache
    ADD CONSTRAINT embedding_cache_pkey PRIMARY KEY (content_hash, model);
