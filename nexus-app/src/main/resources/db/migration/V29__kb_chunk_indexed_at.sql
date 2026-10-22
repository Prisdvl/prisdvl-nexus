-- 切片的「最近一次成功写入检索索引」时间。
--
-- 之前用 kb_chunk.updated_at 的 MAX 近似：那一列是 ON UPDATE CURRENT_TIMESTAMP(3)，
-- 这行只要有任何字段被改（重新分块、改标题、清回 PENDING）就会被刷新，
-- 于是「刚改过但还没重建索引」的库会被显示成已索引 —— 用户点检索却查不到，
-- 正是这一轮要消灭的那类静默失败。
--
-- 回填：已有的 DONE 行没有真实时间可考，用 updated_at 兜底（它们此刻确实在索引里）。
ALTER TABLE kb_chunk
    ADD COLUMN indexed_at DATETIME(3) DEFAULT NULL COMMENT '最近一次成功写入检索索引的时间（NULL = 从未写入）';

UPDATE kb_chunk SET indexed_at = updated_at WHERE es_status = 'DONE';
