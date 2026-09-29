-- ============================================================
-- V1 账号与权限基线
--
-- 与测试/开发用的 H2 脚本（src/main/resources/db/h2/schema.sql）保持语义一致：
--   H2 用 MySQL 兼容模式，但仍有方言差异（ENGINE / COLLATE / ON UPDATE CURRENT_TIMESTAMP），
--   因此两边各写一份。真正的一致性由测试保证——任何一边与代码不匹配都会让测试失败。
-- ============================================================

CREATE TABLE sys_user (
    id            BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    email         VARCHAR(128)          DEFAULT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，绝不存明文',
    nickname      VARCHAR(64)           DEFAULT NULL,
    avatar_url    VARCHAR(512)          DEFAULT NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    last_login_at DATETIME(3)           DEFAULT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    -- 把 deleted 放进唯一键：软删后同名可以重新注册（MySQL 唯一索引允许多个 NULL，但 deleted 非空）
    UNIQUE KEY uk_user_username (username, deleted),
    UNIQUE KEY uk_user_email (email, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户';

CREATE TABLE sys_role (
    id          BIGINT      NOT NULL,
    code        VARCHAR(32) NOT NULL COMMENT '角色码，写入 JWT 的 roles 声明，属于对外契约',
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(255)         DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色字典';

CREATE TABLE sys_user_role (
    id      BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户角色关联';

-- 预置角色。Flyway 迁移只执行一次，因此用普通 INSERT 即可。
INSERT INTO sys_role (id, code, name, description) VALUES
    (1, 'USER', '普通用户', '默认角色，注册时自动授予'),
    (2, 'ADMIN', '管理员', '可访问 /api/admin/**');
