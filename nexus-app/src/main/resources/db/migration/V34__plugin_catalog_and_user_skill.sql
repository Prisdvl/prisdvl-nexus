-- ============================================================
-- 目标：插件目录与 SKILL.md 正文离开 localStorage，落到服务端
-- 只增不改：只新增两张表，不动 user_plugin 与任何既有表
-- 为什么：
--   1. 市场目录此前活在前端 plugins.ts —— 那份清单换个发行版本就漂移，
--      而"市场里有什么"是产品数据，应该由服务端说了算；前端只负责画。
--      目录先按启动时 seed 落地（PluginCatalogService），读取一律走后端；
--   2. 导入的 SKILL.md 正文此前是 localStorage，换设备/换账号即丢。
--      正文是用户的资产，不是界面偏好。
-- ============================================================

-- 插件目录（市场里有哪些东西）。
-- 这一行是"目录版本"：seed 由 PluginCatalogService 提供，
-- seed_version 变了才重写展示字段，平时启动不动它（启动不做无意义写）。
CREATE TABLE plugin_catalog
(
    code          VARCHAR(64)  NOT NULL COMMENT '目录里的插件 id，如 web-fetch',
    name          VARCHAR(128) NOT NULL COMMENT '展示名',
    author        VARCHAR(64)           DEFAULT NULL,
    version       VARCHAR(32)           DEFAULT NULL,
    category      VARCHAR(32)  NOT NULL DEFAULT 'agent' COMMENT 'agent/knowledge/search/automation/productivity/data/skill',
    description   VARCHAR(512)          DEFAULT NULL,
    icon          VARCHAR(255)          DEFAULT NULL COMMENT '图标 URL；空 = 前端用首字母占位',
    rating        DECIMAL(2, 1)         DEFAULT NULL,
    downloads     VARCHAR(16)           DEFAULT NULL,
    tools_json    TEXT                  DEFAULT NULL COMMENT '对外暴露的工具名列表（JSON 数组）',
    tags_json     TEXT                  DEFAULT NULL COMMENT '标签（JSON 数组）',
    verified      TINYINT      NOT NULL DEFAULT 0,
    official      TINYINT      NOT NULL DEFAULT 0,
    updated_label VARCHAR(16)           DEFAULT NULL COMMENT '目录里的"更新于"，展示用',
    sort_order    INT          NOT NULL DEFAULT 100 COMMENT '越小越靠前',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '0 = 已从目录下架（seed 时发现不再提供）',
    seed_version  VARCHAR(32)           DEFAULT NULL COMMENT '最后一次写入本行的 seed 版本',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件目录（启动时 seed，读取只走服务端）';

-- 用户级 SKILL.md 正文。
-- 每用户每 code 至多一行：同名技能再导入一次就是覆盖（与 user_plugin 的 upsert 同一条理由）。
CREATE TABLE user_skill
(
    id          BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL COMMENT '所有者：所有查询必须带此条件',
    code        VARCHAR(64) NOT NULL COMMENT '技能 code，如 skill-demo',
    name        VARCHAR(128) NOT NULL,
    version     VARCHAR(32)  DEFAULT NULL,
    description VARCHAR(512) DEFAULT NULL,
    content_md  MEDIUMTEXT            DEFAULT NULL COMMENT 'SKILL.md 正文（上限 200 KB，服务端校验）',
    enabled     TINYINT     NOT NULL DEFAULT 1,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_skill UNIQUE (user_id, code),
    KEY idx_user_skill_user (user_id, deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户级 SKILL.md 正文（每用户每技能至多一行）';
