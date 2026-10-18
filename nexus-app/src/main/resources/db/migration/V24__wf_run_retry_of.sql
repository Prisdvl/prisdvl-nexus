-- ============================================================
-- V24 wf_run 增加「重试自哪次运行」
--
-- 背景：retry 以**源运行所用的同一版本**新建一次运行（triggerType=RETRY，图不可变），
-- 但表里没有指向源运行的列，于是运行历史只能看出「这是一次重试」，
-- 无法把重试与它续跑的那次运行对上（WorkflowRuntimeService.retry 的 javadoc
-- 曾把这一点记为「已知缺口：要补需要一次迁移」）。本迁移就是那一次迁移。
--
-- 只增不改：NULL = 非重试（含历史数据），重试运行的值为源运行 id。
--
-- 注意：测试环境用 H2（MySQL 模式）执行同一批迁移，而 H2 不接受 MySQL 的
-- `ADD COLUMN a ..., ADD COLUMN b ...` 逗号连写形式（见 V10 的说明），
-- 因此列与索引拆成两条语句。
-- ============================================================

ALTER TABLE wf_run
    ADD COLUMN retry_of_run_id BIGINT NULL
        COMMENT '重试自哪次运行（triggerType=RETRY 时指向源运行；否则 NULL）' AFTER trigger_type;

CREATE INDEX idx_wf_run_retry_of ON wf_run (retry_of_run_id);
