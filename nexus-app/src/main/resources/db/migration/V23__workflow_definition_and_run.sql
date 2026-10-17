-- ============================================================
-- V23 Workflow 编排最小闭环
--
-- 目标：把静态画布原型升级为可持久化、可发布、可运行的 DAG 工作流。
-- 只增不改：全部是新表，不动 V1-V22。
-- 图定义保存在 graph_json 中；位置、节点类型、配置和连线一起快照进版本。
-- ============================================================

CREATE TABLE wf_workflow (
    id                   BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件',
    workspace_id         BIGINT       NOT NULL COMMENT '所属工作区',
    name                 VARCHAR(128) NOT NULL,
    description          VARCHAR(512)          DEFAULT NULL,
    draft_version_id     BIGINT                DEFAULT NULL,
    published_version_id BIGINT                DEFAULT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_wf_workflow_user (user_id, deleted, updated_at),
    KEY idx_wf_workflow_workspace (workspace_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流主表';

CREATE TABLE wf_version (
    id             BIGINT      NOT NULL,
    workflow_id    BIGINT      NOT NULL,
    version_no     INT         NOT NULL,
    schema_version INT         NOT NULL DEFAULT 1,
    graph_json     TEXT        NOT NULL,
    graph_hash     VARCHAR(64)          DEFAULT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED',
    created_by     BIGINT      NOT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at   DATETIME(3)          DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wf_version_workflow_no (workflow_id, version_no),
    KEY idx_wf_version_workflow (workflow_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流不可变版本';

CREATE TABLE wf_run (
    id              BIGINT       NOT NULL,
    workflow_id     BIGINT       NOT NULL,
    version_id      BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED',
    trigger_type    VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    input_json      TEXT                  DEFAULT NULL,
    output_json     TEXT                  DEFAULT NULL,
    error_code      INT                   DEFAULT NULL,
    error_message   VARCHAR(1000)         DEFAULT NULL,
    started_at      DATETIME(3)           DEFAULT NULL,
    finished_at     DATETIME(3)           DEFAULT NULL,
    duration_ms     BIGINT                DEFAULT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_wf_run_workflow (workflow_id, created_at),
    KEY idx_wf_run_user_status (user_id, status, created_at),
    KEY idx_wf_run_version (version_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流运行';

CREATE TABLE wf_node_run (
    id            BIGINT       NOT NULL,
    run_id        BIGINT       NOT NULL,
    node_id       VARCHAR(64)  NOT NULL,
    node_type     VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED',
    attempt       INT          NOT NULL DEFAULT 1,
    input_json    TEXT                  DEFAULT NULL,
    output_json   TEXT                  DEFAULT NULL,
    error_code    INT                   DEFAULT NULL,
    error_message VARCHAR(1000)         DEFAULT NULL,
    latency_ms    BIGINT                DEFAULT NULL,
    started_at    DATETIME(3)           DEFAULT NULL,
    finished_at   DATETIME(3)           DEFAULT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_wf_node_run_run (run_id, node_id),
    KEY idx_wf_node_run_status (run_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流节点执行轨迹';
