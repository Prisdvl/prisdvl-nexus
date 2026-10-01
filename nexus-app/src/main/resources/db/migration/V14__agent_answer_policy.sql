-- ============================================================
-- V14 Agent 回答策略（平台化 4.4）：没有可引用的资料时怎么答
--
-- 背景：在此之前 AGENT 模式只有一种行为——检索不到就拒答，而它是**写死在提示词里的**
-- （AgentPrompts.AGENT_SYSTEM 旧规则 2/3，配合 KnowledgeSearchTool 回灌的那句话）。
-- 后果落在一个知识库都没有的新用户身上：第一次提问必被拒，且拒绝本身没有信息量。
-- 本列把"没有资料时能用自身知识吗"从提示词里拿出来，变成用户可配的策略。
--
-- 两个取值（见 nexus-ai 的 AnswerPolicy）：
--   KB_PREFERRED —— 笔记优先；没有可引用资料时可凭自身知识回答，但必须注明"不是来自你的笔记"（默认）
--   KB_ONLY      —— 只依据笔记，没有就说没找到（4.4 之前的旧行为，现在是显式选择）
--
-- 只增不改：agent 一条 ADD COLUMN。
-- 注意：每条 ADD COLUMN 单独一条 ALTER（H2 不接受 MySQL 的逗号连写，与 V10/V12 同）。
--
-- 【为什么不在 SQL 里回填/改写存量提示词】
-- 存量内置行的 system_prompt 里存着旧提示词（写着"不要用自身知识回答"），与 KB_PREFERRED
-- 矛盾。但本项目 migration 目录**全是 DDL、没有 DML 先例**，且一段中文长字面量写进 SQL
-- 会立刻变成"第三份提示词真源"。改由 AgentService.resolveDefault 识别
-- "这一行还是机器播种的那份文本"后就地升级（与它已有的"内置行就地纠正"同一个位置，
-- 见 docs/decisions.md B.41）。用户自己改过的提示词一个字都不动。
--
-- 【为什么默认是 KB_PREFERRED 而不是"更安全"的 KB_ONLY】
-- 旧行为不是被选的，它只是当时唯一的行为；它恰是本次要修的那个问题。
-- 需要严格的人显式选 KB_ONLY，而不是让所有人都默默继承一个已知有问题的默认。
-- ============================================================

ALTER TABLE agent
    ADD COLUMN answer_policy VARCHAR(16) NOT NULL DEFAULT 'KB_PREFERRED'
        COMMENT '没有可引用资料时的回答策略：KB_PREFERRED = 可凭自身知识回答但须注明不是笔记（默认）；KB_ONLY = 只依据笔记，没有就说没找到'
        AFTER model_role;
