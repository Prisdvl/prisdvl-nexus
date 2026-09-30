package com.lky.nexus.app.chat.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 会话记忆（Chat Memory）装配。
 *
 * <p>这里显式声明 {@link ChatMemory}，而不是完全依赖 Spring AI 的自动配置，
 * 原因是我们需要自己控制<b>窗口大小</b>（可配置），并让"用哪个仓库"这件事在代码里可见。
 *
 * <p><b>仓库也必须显式声明</b>（下面第 2 个 Bean）——这条是血的教训，见下。
 *
 * <p>再次强调职责边界（ADR-011）：
 * <ul>
 *   <li><b>ChatMemory</b>（这里）—— 决定"这次请求发给模型多少历史"，有界、可淘汰；</li>
 *   <li><b>chat_message</b>（本项目自建表）—— 完整历史，供用户查看、评测与观测。</li>
 * </ul>
 * 两者并存、各自解决不同问题，不要试图用一个替代另一个。
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 会话记忆的存储仓库：**只用主数据源**（业务库），不依赖 Spring AI 的自动配置。
     *
     * <p>为什么必须自己声明（`local` profile 实测踩到，2026-09-30）：自动配置那个 Bean 的方法签名是
     * {@code (JdbcTemplate, DataSource)}，它<b>分别注入两个 bean</b>——方言取自 DataSource，
     * 执行用的 JdbcTemplate 取自 JdbcTemplate。本项目有<b>两个</b>数据源（业务 MySQL + 向量 Postgres），
     * 而上下文里唯一的 JdbcTemplate 是 {@code vectorJdbcTemplate}，于是两者错位：
     * 方言按 MySQL 生成（反引号），SQL 却发到 Postgres 执行——
     * <pre>
     * bad SQL grammar [SELECT content, type, `timestamp` FROM SPRING_AI_CHAT_MEMORY …]
     * Caused by: org.postgresql.util.PSQLException: ERROR: syntax error at or near "FROM"
     * </pre>
     * 表现是**所有流式对话在 `local` 下直接失败**（H2 的两个 profile 里向量数据源被
     * {@code nexus.vector.enabled=false} 关掉，只剩一个 JdbcTemplate，所以测试全绿、问题藏到最后）。
     *
     * <p>Spring AI 其实在启动时就警告过，只是夹在 INFO 里很容易漏掉：
     * <pre>
     * WARN …JdbcChatMemoryRepository$Builder - Explicitly set dialect MysqlChatMemoryRepositoryDialect
     *      will be used instead of detected dialect PostgresChatMemoryRepositoryDialect from datasource
     * </pre>
     *
     * <p>修法就是 {@code PrimaryDataSourceConfig} 那条既定规则的又一次应用：
     * <b>多数据源场景下不要依赖自动配置</b>。这里方言与执行都从**同一个** DataSource 推导，
     * 两者不可能再错位；显式 {@code @Qualifier("dataSource")} 而不是靠 {@code @Primary} 兜底，
     * 因为本次故障的成因正是"靠上下文里的唯一候选去猜"。
     */
    @Bean
    public JdbcChatMemoryRepository chatMemoryRepository(
            @Qualifier("dataSource") DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(new JdbcTemplate(dataSource))
                .dataSource(dataSource)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                 @Value("${nexus.chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}
