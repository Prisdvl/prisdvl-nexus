-- ============================================================
-- V21 用户登录会话（Refresh Token + 设备会话管理）
--
-- 背景：此前只有短寿命 access JWT（默认 30m），过期只能重新登录；
-- 无 refresh token、无设备会话表、无法主动踢掉某台设备。
-- 本迁移落地企业常见的 Access + Refresh Rotation + Session 模型，
-- 不改 JWT Payload（仍是 sub + ver），不删 token_version。
--
-- 【为什么存 hash 而不是明文】
-- refresh token 是长期凭证；库泄露时明文可直接冒充会话。
-- 客户端持有 opaque 明文，服务端只存 SHA-256 十六进制摘要，
-- 查询与 rotation 一律用 hash 定位。
--
-- 【为什么不用 JWT 当 refresh】
-- refresh 需要可撤销、可 rotation、可按设备列举；opaque + 行级状态更直接。
--
-- H2 以 MODE=MySQL 执行本脚本，因此沿用 MySQL 方言。
-- ============================================================

CREATE TABLE sys_user_session (
    id            BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL COMMENT '会话所属用户；列表/撤销必须带此条件',
    token_hash    VARCHAR(64)  NOT NULL COMMENT 'refresh token 的 SHA-256 十六进制，绝不存明文',
    device_name   VARCHAR(128)          DEFAULT NULL COMMENT '从 User-Agent 粗解析的设备名，可空',
    user_agent    VARCHAR(512)          DEFAULT NULL,
    ip_address    VARCHAR(64)           DEFAULT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_used_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at    DATETIME(3)  NOT NULL COMMENT 'refresh 过期时间',
    revoked       TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 已撤销（logout / rotation / 改密）',
    revoked_at    DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (id),
    -- token_hash 唯一索引同时承担查找（等价于 idx_session_token_hash）
    UNIQUE KEY idx_session_token_hash (token_hash),
    KEY idx_session_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户登录会话（refresh token hash）';
