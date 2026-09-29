-- PostgreSQL 初始化：创建向量检索所需的扩展
--
-- 注意：Spring AI 的 PgVectorStore 建表需要 vector / hstore / uuid-ossp 三个扩展。
-- 表结构（vector_store）由 Step 0.6 的 Flyway 迁移创建（initialize-schema=false），
-- 这里只负责"把扩展准备好"，避免应用启动时因缺扩展而失败。
--
-- 本脚本只在数据卷为空（首次启动）时执行一次。

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 验证扩展已就绪（在 docker compose logs postgres 里能看到）
DO $$
BEGIN
    RAISE NOTICE 'prisdvl-nexus: pgvector extension ready -> %', (SELECT extversion FROM pg_extension WHERE extname = 'vector');
END
$$;
