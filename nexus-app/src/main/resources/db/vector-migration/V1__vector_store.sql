-- ============================================================
-- 向量库（PostgreSQL + pgvector）—— 由独立的一套 Flyway 管理
--
-- 为什么向量不放在 MySQL：
--   ① MySQL 没有向量类型与 ANN 索引，把 1024 维浮点塞进 BLOB 后
--      只能在应用层做全量暴力计算（1 万个切片 × 1024 维 = 每次都算 1000 万次乘法）；
--   ② pgvector 提供 <=> 余弦距离与 HNSW 索引，把"最近邻"变成索引扫描。
--
-- 维度固定为 1024：与知识库创建时冻结的维度一致（ADR-008，bge-m3）。
-- 若将来换用不同维度的嵌入模型，必须新建列/表并重建索引——pgvector 会在写入时
-- 直接报维度不匹配，这正是我们想要的"早失败"。
--
-- 前置条件：数据库已安装 vector 扩展（deploy/postgres/init/01-extensions.sql 已装）。
-- 刻意不在迁移里执行 CREATE EXTENSION：扩展属于数据库实例级配置，
-- 由部署脚本用超级用户完成，应用账号不应需要该权限。
-- ============================================================

CREATE TABLE IF NOT EXISTS kb_vector (
    -- 主键就是 MySQL kb_chunk.id：不做自增，避免两库各自生成 id 后对不上号
    chunk_id     BIGINT       PRIMARY KEY,
    kb_id        BIGINT       NOT NULL,
    document_id  BIGINT       NOT NULL,
    content_hash CHAR(64)     NOT NULL,
    embedding    vector(1024) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_vector_kb ON kb_vector (kb_id);
CREATE INDEX IF NOT EXISTS idx_kb_vector_document ON kb_vector (document_id);
CREATE INDEX IF NOT EXISTS idx_kb_vector_hash ON kb_vector (content_hash);

-- HNSW（近似最近邻）索引：把检索从"全表算距离"变成"图搜索"。
-- 参数含义：m=每层最大连接数（越大越准越占内存），
--          ef_construction=建索引时的候选队列（越大越准、建得越慢）。
-- 这两个值是精度/成本旋钮，先取 pgvector 的常用默认量级，Phase 3 评测时再调。
CREATE INDEX IF NOT EXISTS idx_kb_vector_hnsw
    ON kb_vector USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ============================================================
-- 嵌入缓存：内容相同 → 向量相同 → 不必重复调用模型
--
-- 为什么值得单独一张表：增量同步会重切分文档，而"同一段内容出现在多篇笔记里"
-- 也很常见（模板、抄录、代码片段）。命中的话省下的既是时间也是真金白银。
-- 键用 content_hash，与 kb_chunk.content_hash 同一套算法（SHA-256）。
-- ============================================================
CREATE TABLE IF NOT EXISTS embedding_cache (
    content_hash CHAR(64)     PRIMARY KEY,
    model        VARCHAR(64)  NOT NULL,
    embedding    vector(1024) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
