-- ============================================================
-- V16 Agent 采样温度（产品化 Phase 5.4 · P1-8，见 docs/productization-gap-analysis.md）
--
-- 背景：temperature 此前只有一个全局值（ai.defaults.temperature，默认 0.7），在
-- AiModelRegistry 建 ChatModel 时写死进去。想写严谨回答的 Agent 和想写发散头脑风暴的
-- Agent 只能共用同一个 0.7，用户无从调整。
--
-- 本列把它变成 Agent 级配置。**生效点在 AgentRuntime.configure**（两条链路——一次性 ask
-- 与流式对话——唯一的公共装配点），以请求级 options 覆盖模型默认值，机制见
-- docs/decisions.md B.45。因此它只作用于"由 Agent 驱动的调用"：PLAIN / RAG 会话与
-- /api/knowledge/**/ask 不走 configure，仍用全局默认——这是生效范围的诚实边界，不是遗漏。
--
-- 【NULL 与 0 是两件事，不要合并】
--   NULL = 没配过，跟随全局 ai.defaults.temperature（可被运维统一调整）
--   0    = 显式要求最高确定性（贪心解码）
-- 用 0 当"未配置"的哨兵值，等于让"我想要 0"这件事永远表达不出来。
--
-- 范围 0–2 在服务层校验（越界报 1001）。列宽 DECIMAL(3,2) 结构上就容不下 0–2 之外的大数，
-- 是第二道防线而不是唯一防线：服务层那条才是给出可读错误的那条。
--
-- 只增不改：agent 一条 ADD COLUMN，可空、无默认值，存量行自动为 NULL（= 行为与加列前完全一致）。
-- 每条 ADD COLUMN 单独一条 ALTER（H2 不接受 MySQL 的逗号连写，见 V10/V12 注释）。
-- ============================================================

ALTER TABLE agent
    ADD COLUMN temperature DECIMAL(3,2) NULL
        COMMENT '采样温度 0.00-2.00；NULL = 跟随全局 ai.defaults.temperature（0 是"显式要求确定性"，与 NULL 不同义）'
        AFTER answer_policy;
