-- ============================================================
-- V22 工具授权与 Agent 人工审批闭环（P0）
--
-- 目标：把 V13 文件头里刻意推迟的 permission / approval 真正落地——
--   tool.permission            → ai_tool_registry.permission（注册元数据，运行时权威）
--   审批挂起-恢复机制           → ai_agent_run / ai_agent_run_step / ai_tool_approval
--   用户级长期授权              → ai_tool_grant
--   工具调用审计                → ai_tool_invocation
--
-- 【权限为什么单独一张注册表，而不加在 tool 表】
-- tool 表是**按用户的覆盖行**（"这个用户关了哪些工具"，无行 = 代码侧默认启用）。
-- permission 是工具**固有的风险等级**，与用户无关；混进 tool 表会立刻产生歧义：
-- 同一个工具的权限，A 用户的覆盖行说 WRITE、B 用户没有行，听谁的？
-- 所以另建 ai_tool_registry：每个工具 code 恰好一行，启动时从代码声明幂等同步。
--
-- 【permission 的权威性】数据库中已有的 permission 是运行时权威配置：
-- 同步只在**首次创建**时写入代码声明的默认值，之后不再无条件覆盖
-- （否则管理员把某工具降级为 DANGEROUS 的动作会在下次重启时被悄悄改回去）。
--
-- 【audit 表不建 deleted 列】它们是只追加的执行轨迹，没有"逻辑删除"这个业务动作；
-- 建了 deleted 反而会让"这条记录为什么查不到"多一种解释。
--
-- 只增不改：全部是新表，不动任何现有表/列。与 V12/V13 的约定一致。
-- ============================================================

CREATE TABLE ai_tool_registry (
    id          BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL COMMENT '工具标识，来自实现的 @Tool(name=...)（如 knowledge_search）',
    name        VARCHAR(128) NOT NULL COMMENT '界面显示名',
    description VARCHAR(1024)         DEFAULT NULL COMMENT '给模型看的描述摘要（权威副本仍是代码里的 @Tool 注解）',
    source      VARCHAR(16)  NOT NULL DEFAULT 'BUILTIN' COMMENT 'BUILTIN / MCP',
    category    VARCHAR(32)  NOT NULL DEFAULT 'OTHER' COMMENT 'RETRIEVAL / COMPUTE / NETWORK / OTHER',
    permission  VARCHAR(16)  NOT NULL DEFAULT 'READ' COMMENT 'READ / WRITE / DANGEROUS；运行时权威配置，同步不覆盖既有值',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '0 = 全平台禁用该工具（默认拒绝执行）',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_tool_registry_code (code),
    KEY idx_ai_tool_registry_enabled (enabled, permission)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具注册元数据（每个 code 一行；permission 为运行时权威）';

CREATE TABLE ai_agent_run (
    id              BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL COMMENT '归属用户：所有查询必须带此条件',
    conversation_id BIGINT                DEFAULT NULL COMMENT '发起运行时的会话（流式对话路径有值）',
    agent_id        BIGINT                DEFAULT NULL COMMENT '使用的 Agent（空 = 该用户的内置默认 Agent）',
    question        TEXT                  DEFAULT NULL COMMENT '本轮问题原文（审计用）',
    status          VARCHAR(32)  NOT NULL COMMENT 'RUNNING / WAITING_APPROVAL / DONE / FAILED / MAX_ITERATIONS / CANCELLED',
    max_iterations  INT          NOT NULL DEFAULT 8 COMMENT '本轮允许的最大模型迭代次数',
    iteration_count INT          NOT NULL DEFAULT 0 COMMENT '已执行的模型迭代次数',
    model_role      VARCHAR(64)           DEFAULT NULL,
    answer          TEXT                  DEFAULT NULL,
    error_code      INT                   DEFAULT NULL,
    error_msg       VARCHAR(1000)         DEFAULT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at      DATETIME(3)           DEFAULT NULL,
    finished_at     DATETIME(3)           DEFAULT NULL,
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_agent_run_user (user_id, created_at),
    KEY idx_ai_agent_run_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 运行（状态机：可挂起等待审批）';

CREATE TABLE ai_agent_run_step (
    id           BIGINT      NOT NULL,
    run_id       BIGINT      NOT NULL,
    seq          INT         NOT NULL COMMENT '运行内递增序号，唯一且决定展示顺序',
    step_type    VARCHAR(16) NOT NULL COMMENT 'THINK / TOOL_CALL / OBSERVATION / ANSWER',
    tool_code    VARCHAR(64)          DEFAULT NULL COMMENT '仅 TOOL_CALL / OBSERVATION 有值',
    input_digest VARCHAR(2000)         DEFAULT NULL COMMENT '已脱敏的入参摘要',
    output_digest VARCHAR(2000)        DEFAULT NULL COMMENT '已脱敏的输出摘要',
    status       VARCHAR(16) NOT NULL DEFAULT 'OK' COMMENT 'OK / FAILED / DENIED / CANCELLED',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_agent_run_step_seq (run_id, seq),
    KEY idx_ai_agent_run_step_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 运行步骤轨迹（不含隐藏思维链原文）';

CREATE TABLE ai_tool_approval (
    id               BIGINT       NOT NULL,
    run_id           BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    invocation_id    BIGINT                DEFAULT NULL COMMENT '关联的工具调用（决定"批准的是哪一次调用"）',
    tool_code        VARCHAR(64)  NOT NULL,
    arguments_digest VARCHAR(2000)         DEFAULT NULL COMMENT '已脱敏的待执行入参',
    reason           VARCHAR(500)          DEFAULT NULL COMMENT '需要审批的原因',
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / DENIED / CANCELLED / EXPIRED',
    decision_scope   VARCHAR(16)           DEFAULT NULL COMMENT 'once / always / deny',
    decision_reason  VARCHAR(500)          DEFAULT NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    decided_at       DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_tool_approval_run (run_id, status),
    KEY idx_ai_tool_approval_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具调用的人工审批记录';

CREATE TABLE ai_tool_invocation (
    id               BIGINT       NOT NULL,
    run_id           BIGINT                DEFAULT NULL,
    step_id          BIGINT                DEFAULT NULL,
    user_id          BIGINT       NOT NULL,
    tool_code        VARCHAR(64)  NOT NULL,
    permission       VARCHAR(16)  NOT NULL COMMENT '执行时数据库中实际生效的 permission',
    arguments_digest VARCHAR(2000)         DEFAULT NULL,
    approval_id      BIGINT                DEFAULT NULL,
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING_APPROVAL / SUCCEEDED / FAILED / DENIED / CANCELLED',
    result_digest    VARCHAR(2000)         DEFAULT NULL,
    error_code       INT                   DEFAULT NULL,
    started_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at      DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_tool_invocation_run (run_id, started_at),
    KEY idx_ai_tool_invocation_user_tool (user_id, tool_code, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工具调用审计（含被拒/未执行的调用）';

CREATE TABLE ai_tool_grant (
    id         BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    tool_code  VARCHAR(64) NOT NULL,
    scope      VARCHAR(16) NOT NULL DEFAULT 'ALWAYS' COMMENT '长期授权只允许 ALWAYS',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_tool_grant_user_tool (user_id, tool_code),
    KEY idx_ai_tool_grant_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户级长期工具授权（scope=always）';
