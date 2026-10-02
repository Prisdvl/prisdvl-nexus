-- ============================================================
-- V17 消息上的 Agent 溯源（产品化 Phase 5.4 · P1-14，见 docs/productization-gap-analysis.md）
--
-- 背景："这条回答是哪个 Agent 跑的"今天答不出来。chat_conversation.agent_id 只在会话上
-- 记了一个**当前选择**，而它是活的（可以随时改），所以历史消息无法据此回溯；会话不选 Agent 时
-- 它还是 NULL，连"其实跑的是内置默认助手"这件事都看不出来。
--
-- 【两列同名，语义不同，不是重复存储——后来者不要删掉其中一个】
--   chat_conversation.agent_id = **活指针**：这个会话现在用哪个 Agent（NULL = 跟随内置默认）
--   chat_message.agent_id      = **历史事实**：这条消息当时实际跑的是哪一行
-- 前者会变，后者插入即定；前者为 NULL 时后者仍会落内置默认那一行的真实 id。
-- 把它们当一个东西，等于放弃"改了会话的 Agent 之后，之前那些回答还能查到出处"。
--
-- 【只记助手消息，用户消息恒为 NULL】
-- 用户消息不是任何 Agent 产出的，给它写 agentId 是把"谁说的话"记错人；而且按 agent_id
-- 聚合时会把每轮对话重复计两次。会话级的归属已由 chat_conversation.agent_id 承载。
-- 落库点见 ChatMessageMapper.appendAssistantMessage（agentId 是插入参数、不可被 update 覆盖）。
--
-- 【索引为什么带 user_id 前缀，而不是裸 agent_id】
-- 这是多租户表，全仓查询一律带 user_id 条件（同表的 idx_msg_conv 也是
-- (conversation_id, user_id, id) 这种带归属列的形状）。裸 agent_id 索引在本表上
-- 只会被跨租户的查询用上——那种索引的存在本身就会**诱导**后来者写出漏 user_id 的查询。
-- 与 P1-14 的聚合查询（GET /api/observability/agents，GROUP BY agent_id WHERE user_id=?）
-- 同批落地：字段与读它的机制同一次改动，不留"加了列没人读"的中间态。
--
-- 只增不改：一条 ADD COLUMN + 一条 CREATE INDEX。存量消息为 NULL（= 未知出处，如实表达）。
-- 每条 ADD COLUMN 单独一条 ALTER（H2 不接受 MySQL 的逗号连写，见 V10/V12 注释）。
-- ============================================================

ALTER TABLE chat_message
    ADD COLUMN agent_id BIGINT NULL
        COMMENT '产出这条助手消息的 Agent（历史事实，插入即定；用户消息恒为 NULL）'
        AFTER user_id;

CREATE INDEX idx_msg_user_agent ON chat_message (user_id, agent_id, id);
