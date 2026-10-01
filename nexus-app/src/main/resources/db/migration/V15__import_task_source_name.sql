-- ============================================================
-- V15 单文件上传（产品化 Phase 5.3 · P1-1，见 docs/productization-gap-analysis.md）
--
-- 背景：此前导入只有一条入口——上传整个 Obsidian Vault 的 ZIP。用户手上有一份
-- PDF / DOCX / TXT / MD 想单独放进知识库时，没有任何办法（只能为了一个文件去
-- 打包一个 ZIP，再让增量同步去猜它属于哪次同步）。
--
-- 做法是复用同一条异步导入链路（不新建第二条流水线），靠 kb_import_task.task_type
-- 区分：VAULT_ZIP 从压缩包里读出文件清单，UPLOAD 只有一个文件，
-- 它的文件名必须存在任务行上——因为执行器是在消费者线程里另起的，
-- 那时已经没有 HTTP 请求可以问"用户传的是哪个文件"了。
--   * 暂存区里存的是文件字节而不是名字（ImportStagingStore 按 taskId 命名），
--     所以名字必须单独落一列；
--   * 叫 source_name 而不是 file_name：它最终会变成 kb_document.source_path，
--     与 kb_import_task_item.source_path 命名的同一类东西。
--
-- 宽度取 VARCHAR(512)，与 kb_document.source_path / kb_import_task_item.source_path
-- 一致：列宽不一致会在"截断"上产生两个不同的上限，属于自找的麻烦。
--
-- VAULT_ZIP 任务的这一列为 NULL（源路径在压缩包条目里，不在这里）。
--
-- 每条 ADD COLUMN 单独一条 ALTER（H2 不接受 MySQL 的逗号连写，见 V10/V12 注释）。
-- ============================================================

ALTER TABLE kb_import_task
    ADD COLUMN source_name VARCHAR(512) NULL
        COMMENT '单文件上传的原始文件名（UPLOAD 专用；VAULT_ZIP 为 NULL，源路径从压缩包条目读出）';
