-- ============================================================
-- V3 Spring AI 会话记忆表（SPRING_AI_CHAT_MEMORY）
--
-- 这份 DDL 抄自 Spring AI 2.0.1 自带脚本，请勿手改字段定义：
--   spring-ai-model-chat-memory-repository-jdbc-2.0.1.jar
--     -> org/springframework/ai/chat/memory/repository/jdbc/schema-mysql.sql
--
-- 【为什么由我们自己建表，而不是让框架自动建】
-- 框架的自动建表只对"嵌入式数据库"生效（initialize-schema=embedded）。
-- 如果我们让框架管 H2、让 Flyway 管 MySQL，就等于有两套 DDL 来源：
-- 一旦 Spring AI 升级并改动表结构（例如 2.0 新增的 sequence_id），
-- H2 测试会跟着框架走而通过，MySQL 生产却会因迁移脚本过时而失败——这是最危险的组合。
-- 因此这里明确规定：**本项目的 DDL 由我们自己拥有**（H2 脚本 + Flyway 迁移），
-- 应用侧统一设置 initialize-schema=never。若我们的表结构与框架期望不符，
-- 测试会立刻失败，而不是等上线才暴露。
--
-- 注意 conversation_id 是 VARCHAR(36)：记忆 key 必须放得下（见 ChatMemoryKeys）。
-- ============================================================

CREATE TABLE SPRING_AI_CHAT_MEMORY (
    `conversation_id` VARCHAR(36) NOT NULL,
    `content`         TEXT        NOT NULL,
    `type`            ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') NOT NULL,
    `timestamp`       TIMESTAMP   NOT NULL,
    `sequence_id`     BIGINT      NOT NULL,

    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`),
    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX` (`conversation_id`, `sequence_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Spring AI 会话记忆（发给模型的有界窗口）';
