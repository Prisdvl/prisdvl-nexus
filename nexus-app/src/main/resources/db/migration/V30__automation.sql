-- =====================================================================
-- V30 自动化任务（/api/automations）
--
-- 目标：让「自动化」页有真后端——用户存一条 prompt + 排期，后端到点用 Agent 跑一遍，
--       每次跑留一条运行记录（状态 / 耗时 / token / 错误），前端不再读写死的演示数据。
--
-- 只增不改：新增 automation / automation_run 两张表，不动既有表、既有列。
--
-- 为什么拆两张表：定义（多久跑一次、跑什么）与运行（这次跑成什么样）生命周期不同。
--   改定义不该改写历史；查历史要能按任务取最近 N 条，而不是扫全表。
-- 为什么 next_run_at 是独立列：多实例调度靠它做 CAS 抢占——先读出到期时刻，
--   再用 `WHERE next_run_at = 读到的值` 抢一次，抢到的实例才执行，抢不到的自然跳过。
--   换成"读状态再写状态"的两步，两个实例会同时看到"该跑了"，跑两遍。
-- =====================================================================

CREATE TABLE automation
(
    id            BIGINT       NOT NULL COMMENT '雪花 ID（应用生成）',
    user_id       BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件',
    name          VARCHAR(128) NOT NULL COMMENT '任务名',
    prompt        TEXT         NOT NULL COMMENT '到点交给 Agent 的问题',
    agent_id      BIGINT                DEFAULT NULL COMMENT '执行的 Agent；NULL = 用该用户的默认 Agent',
    kb_ids        VARCHAR(512)          DEFAULT NULL COMMENT '逗号分隔的知识库 ID；空 = 不覆盖 Agent 自身的绑定',
    schedule_type VARCHAR(16)  NOT NULL COMMENT 'HOURLY / DAILY / WEEKLY / CUSTOM',
    cron          VARCHAR(64)  NOT NULL COMMENT '生效的 5 段 cron（固定频率按 schedule_type 派生，CUSTOM 为用户自填）',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 暂停',
    next_run_at   DATETIME(3)           DEFAULT NULL COMMENT '下次到期时间；同时也是调度器 CAS 抢占的基准值',
    last_run_at   DATETIME(3)           DEFAULT NULL COMMENT '最近一次运行时间',
    last_status   VARCHAR(16)           DEFAULT NULL COMMENT '最近一次运行状态：SUCCEEDED / FAILED',
    retry_count   INT          NOT NULL DEFAULT 0 COMMENT '连续失败次数：成功清零，用于失败重试退避',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_automation_due (enabled, deleted, next_run_at),
    KEY idx_automation_user (user_id, deleted, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '自动化任务定义';

CREATE TABLE automation_run
(
    id                BIGINT        NOT NULL COMMENT '雪花 ID（应用生成）',
    automation_id     BIGINT        NOT NULL COMMENT '所属任务',
    user_id           BIGINT        NOT NULL COMMENT '所有者（冗余一列：按用户查历史时免 join）',
    trigger_type      VARCHAR(16)   NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 立即跑 / SCHEDULED 到点触发',
    status            VARCHAR(16)   NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUCCEEDED / FAILED',
    answer            TEXT                   DEFAULT NULL COMMENT 'Agent 回答正文（失败时为空）',
    error_code        INT                    DEFAULT NULL COMMENT '失败时的业务码，供前端分辨"模型挂了"还是"参数错了"',
    error_message     VARCHAR(1000)          DEFAULT NULL COMMENT '失败原因：日志会轮转，这一列不会',
    prompt_tokens     INT                    DEFAULT NULL COMMENT '本次运行累计输入 token（未采集到时为 NULL，不写 0 假装知道）',
    completion_tokens INT                    DEFAULT NULL COMMENT '本次运行累计输出 token',
    total_tokens      INT                    DEFAULT NULL COMMENT '输入 + 输出',
    started_at        DATETIME(3)            DEFAULT NULL,
    finished_at       DATETIME(3)            DEFAULT NULL,
    duration_ms       BIGINT                 DEFAULT NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_automation_run_task (automation_id, created_at),
    KEY idx_automation_run_user (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '自动化运行记录';
