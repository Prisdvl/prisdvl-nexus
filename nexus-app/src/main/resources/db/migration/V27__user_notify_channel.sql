-- 通知通道（Webhook / 邮件）：用户自己维护的"事情做完叫我一声"的入口。
--
-- 设计取舍：
--   * target 一列存两种东西（Webhook 是 URL、邮件是地址），而不是分表或两列 —— 通道语义
--     由 type 决定，读的地方要按 type 解析，多一列只会让人忘了同步；
--   * secret 与 target 分开：Webhook 需要签名密钥，邮件不需要，但两者都要能"有但不回传"；
--   * last_* 三列是同一条通道最近一次投递的结果，不做投递历史表 —— 现在只需要让用户看见
--     "上次成没成、报了什么"，历史留给日志；
--   * events 用逗号分隔的事件码，而不是关联表：事件集合是代码里的枚举，量级个位数，
--     关联表会让"改一个通道"变成三张表的写操作；
--   * secret 用 CredentialCipher 加密（v1: 前缀），与 user_embedding_config.api_key 同规。
CREATE TABLE user_notify_channel (
    id          BIGINT       NOT NULL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    target      VARCHAR(500) NOT NULL,
    secret      VARCHAR(500),
    events      VARCHAR(200) NOT NULL DEFAULT '',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    last_status VARCHAR(16),
    last_error  VARCHAR(500),
    last_at     DATETIME(3),
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_notify_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
