-- ============================================================
-- V26 两步验证（TOTP / RFC 6238）
--
-- 背景：设置页的「两步验证」一直是 disabled 占位按钮，文案写「服务端尚未提供
-- TOTP 绑定接口，因此当前不可用」——那是一个假承诺，本迁移把它兑现。
--
-- 【为什么密钥是密文列】totp_secret 是「能生成任意有效验证码」的长期凭据，
-- 泄露等同于绕过第二因子。这里沿用 user_llm_config.api_key 的口径：
-- 落库前经 CredentialCipher（AES-GCM）加密，长度因此放宽到 500；
-- 明文 Base32 密钥只有 32 字符，但密文形如 v1:base64(IV‖密文‖tag)。
-- KEK 未配置时写入 fail-closed（不是退化成明文）——与 V19/V25 同一条约定。
--
-- 【为什么把「已启用」与「密钥」分成两列】setup 会先写入候选密钥（enabled=0），
-- 用户拿验证器扫一次、回填 6 位码校验通过后才把 enabled 置 1。
-- 若共用一个字段，中途放弃的绑定会让该账号下次登录直接要验证码，而用户手里
-- 根本没有能出码的设备——把自己锁在门外。
--
-- 【恢复码单独建表而不是 JSON 列】恢复码是一次性的、需要逐条标记已用，
-- 放 JSON 里无法原子消费（并发下可能同一码用两次）。表里只存 SHA-256 哈希，
-- 明文仅在生成的那一次响应里出现。
--
-- 只增不改：一条 ALTER + 一张新表。禁止改写 V1–V25。
-- H2 以 MODE=MySQL 执行本脚本，因此沿用 MySQL 方言。
-- ============================================================

ALTER TABLE sys_user
    ADD COLUMN totp_secret VARCHAR(500) DEFAULT NULL
        COMMENT 'TOTP 密钥（Base32 明文经 AES-GCM 加密后的密文；未绑定为空）';

ALTER TABLE sys_user
    ADD COLUMN totp_enabled TINYINT NOT NULL DEFAULT 0
        COMMENT '1 = 登录需要第二因子；0 = 未启用（含 setup 了但还没校验通过）';

ALTER TABLE sys_user
    ADD COLUMN totp_confirmed_at DATETIME(3) DEFAULT NULL
        COMMENT '绑定校验通过的时间；仅用于展示"何时开启"，不参与校验';

CREATE TABLE user_recovery_code (
    id         BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL COMMENT '所有者：所有查询必须带此条件',
    code_hash  VARCHAR(64) NOT NULL COMMENT '恢复码的 SHA-256（hex）；明文只在生成响应里出现一次',
    used_at    DATETIME(3) DEFAULT NULL COMMENT '非空 = 已被使用，不可再用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_recovery_user (user_id),
    UNIQUE KEY uk_recovery_hash (user_id, code_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '两步验证的一次性恢复码';
