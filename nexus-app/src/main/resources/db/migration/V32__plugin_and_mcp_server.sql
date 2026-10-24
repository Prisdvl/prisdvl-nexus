-- ============================================================
-- 目标：插件启停落库 + 外部 MCP Server 接入（最小闭环）
-- 只增不改：只新增两张表，不动任何既有表
-- 为什么：
--   1. 插件页此前只有前端 localStorage —— 换设备、换浏览器即丢，
--      而"这个用户开了哪些插件"是服务端该记住的事实，不是界面偏好；
--   2. MCP 此前只有进程内 Server（把我们的工具暴露出去），没有 Client。
--      要接外部 MCP server，就得有"用户配了哪个 server、它的工具是什么、
--      上次探测什么时候成功"这三件事的落点。
-- ============================================================

-- 插件启停与配置。
-- 覆盖行语义与 tool 表一致：没有行 = 按目录里的默认值（见 PluginService），
-- 所以新用户、清库、换环境都不会让插件凭空消失。
CREATE TABLE user_plugin
(
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件',
    plugin_code VARCHAR(64)  NOT NULL COMMENT '目录里的插件 id，如 web-fetch',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    config_json TEXT                  DEFAULT NULL COMMENT '插件配置（键值 JSON），空 = 用默认值',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_plugin UNIQUE (user_id, plugin_code),
    KEY idx_user_plugin_user (user_id, deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '插件启停与配置（每用户每插件至多一行）';

-- 外部 MCP server。
-- slug：工具名 mcp__<slug>__<tool> 里的那一段，由 name 生成（每用户唯一）；
-- tools_json：最近一次 probe 的 tools/list 原始结果，运行期装工具直接读它，
--             不必每次对话都去连一次外部服务；
-- last_error：probe 失败原因，页面必须显示它 —— "静默当成没有工具"是本表存在的反面。
CREATE TABLE mcp_server
(
    id                 BIGINT       NOT NULL,
    user_id            BIGINT       NOT NULL COMMENT '所有者：所有查询必须带此条件',
    name               VARCHAR(64)  NOT NULL COMMENT '展示名，也是 slug 的来源',
    slug               VARCHAR(64)  NOT NULL COMMENT '工具名前缀 mcp__<slug>__<tool>',
    transport          VARCHAR(16)  NOT NULL DEFAULT 'http' COMMENT 'http = streamable HTTP / stdio = 本机子进程',
    url                VARCHAR(512)          DEFAULT NULL COMMENT 'transport=http 时的 endpoint',
    command            VARCHAR(512)          DEFAULT NULL COMMENT 'transport=stdio 时的命令行',
    auth_header_cipher VARCHAR(500)          DEFAULT NULL COMMENT 'Authorization 头，v1: 前缀密文（明文上限 320）',
    enabled            TINYINT      NOT NULL DEFAULT 1,
    last_ok_at         DATETIME(3)           DEFAULT NULL COMMENT '最近一次 probe 成功时间',
    last_error         VARCHAR(1000)         DEFAULT NULL COMMENT '最近一次 probe 失败原因，页面直接显示',
    tools_json         TEXT                  DEFAULT NULL COMMENT 'tools/list 原始结果（运行期装工具读它）',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted            TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mcp_server_slug UNIQUE (user_id, slug),
    KEY idx_mcp_server_user (user_id, deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '外部 MCP server 与最近一次探测结果';
