-- ============================================================
-- V19 用户自定义对话模型凭据（每用户一套 OpenAI 兼容配置）
--
-- 背景：此前对话模型的 base-url / api-key / 模型名只来自服务端环境变量
-- （AI_CHAT_*，见 application.yml 的 ai.providers），所有用户共用一套、
-- 换 key 要改 .env 并重新打包。本迁移允许每个登录用户在「设置 → 模型 Key」里
-- 填入自己的 OpenAI 兼容端点与 Key，对话 / RAG / Agent 生成走他自己的网关与额度；
-- 未填写或未启用时回落服务端全局配置。
--
-- 只放开对话生成，不放开嵌入：嵌入维度在建知识库时已冻结（ADR-008），
-- 切维度会让旧向量全部失效。
--
-- 【为什么是 1:1 用户一张表，而不是塞进 sys_user】
-- sys_user 是账号主体表，塞三个 key 字段会把"账号"与"模型凭据"两件事耦在一起；
-- 独立表便于以后扩字段（如温度上限、自定义 header）而不动用户表，也符合
-- "一个用户一份配置"的语义（user_id 唯一键）。
--
-- 【安全】
-- api_key 是敏感凭证：接口回显时一律掩码（只留前后几位），不与他人共享；
-- 它落在业务库 MySQL（与 sys_user.password_hash 同一防护等级），本期不做字段级加密，
-- 与既有密钥存储口径一致（见 docs/security.md 未实现清单）。
--
-- H2 以 MODE=MySQL 执行本脚本，因此沿用 MySQL 方言（ENGINE / COMMENT / ON UPDATE）。
-- ============================================================

CREATE TABLE user_llm_config (
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件（与知识库同一套隔离约定）',
    base_url    VARCHAR(500) DEFAULT NULL COMMENT 'OpenAI 兼容端点，如 https://api.deepseek.com',
    api_key     VARCHAR(500) DEFAULT NULL COMMENT '该用户自己的 API Key（敏感，接口回显掩码）',
    model_name  VARCHAR(200) DEFAULT NULL COMMENT '模型名，如 deepseek-chat',
    enabled     TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 启用自定义模型；0 = 回落服务端全局配置',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_llm_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户自定义对话模型凭据（OpenAI 兼容）';
