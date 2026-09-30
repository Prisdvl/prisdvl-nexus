-- ============================================================
-- V12 Agent 资源（平台化 Phase 4.1：用户可配置 Agent）
--
-- 目标（§十五/§十六/§十七）：把今天写死在代码里的三样东西变成数据库里的用户资源——
--   AgentPrompts.AGENT_SYSTEM  → agent.system_prompt
--   Role.CHAT_AGENT            → agent.model_role
--   工具扫描"用户的全部知识库"   → agent_knowledge_base（绑定的库，空 = 不限制）
--
-- 依赖方向（§十七）：agent(表) → AgentDefinition(nexus-ai 的值对象) → AgentRuntime(app)
--   → 现有 Agent Loop（不改）。Entity 绝不进 Loop。
--
-- 只增不改：两张新表 + chat_conversation 一条 ADD COLUMN。
-- 每条 ADD COLUMN 单独一条 ALTER（H2 不接受 MySQL 的逗号连写，见 V10 注释）。
--
-- 刻意**不建**的列（宁缺勿假，详见 decisions.md B.35）：
--   max_iterations / timeout_ms / memory_config —— 今天没有任何一处会读它们
--   （轮数上限由 Spring AI 的 ToolCallingManager 决定，记忆窗口是全局单例），
--   先建列只会得到"配置了但不生效"的假旋钮。等对应机制落地时再随该增量加列。
-- ============================================================

CREATE TABLE agent (
    id            BIGINT       NOT NULL,
    workspace_id  BIGINT       NOT NULL COMMENT '所属工作区',
    owner_id      BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件（与知识库同一套隔离约定）',
    code          VARCHAR(64)  NOT NULL COMMENT '稳定标识（小写字母/数字/._-），供将来的 tool/workflow/application 引用；不用中文名当标识',
    name          VARCHAR(128) NOT NULL COMMENT '显示名',
    description   VARCHAR(512)          DEFAULT NULL,
    system_prompt TEXT                  DEFAULT NULL COMMENT 'Agent 的系统提示（可空：空则用内置默认提示）',
    model_role    VARCHAR(64)           DEFAULT NULL COMMENT '模型逻辑角色（如 chat.agent）；空则用该模式的默认角色',
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / DISABLED；只有 PUBLISHED 能被会话选用',
    builtin       TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 系统内置（默认助手）：不可删除、不可停用，可改名改提示词',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 不建 (workspace_id, code, deleted) 唯一键：逻辑删除后 deleted=1 的行之间会互相冲突
    -- （删掉 code=helper 再删一个同名 helper 就撞键）。唯一性在服务层按 deleted=0 校验，
    -- 与 kb_knowledge_base 的同名校验保持同一种做法。
    KEY idx_agent_owner (owner_id, deleted, created_at),
    KEY idx_agent_ws_status (workspace_id, deleted, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent（用户可配置）';

CREATE TABLE agent_knowledge_base (
    id         BIGINT      NOT NULL,
    agent_id   BIGINT      NOT NULL,
    kb_id      BIGINT      NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 关联表是物理行（无逻辑删除），这里的唯一键是安全的：同一个 Agent 不会重复绑同一个库
    UNIQUE KEY uk_agent_kb (agent_id, kb_id),
    KEY idx_agent_kb_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 绑定的知识库';

ALTER TABLE chat_conversation
    ADD COLUMN agent_id BIGINT NULL
        COMMENT 'AGENT 模式会话选用的 Agent（空 = 用该用户的内置默认 Agent）';
