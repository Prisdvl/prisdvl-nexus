package com.lky.nexus.app.infra.vector;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * 向量库（PostgreSQL + pgvector）的独立数据源与迁移。
 *
 * <p><b>为什么是第二个数据源而不是同一个库</b>：业务事实（用户、文档、切片）与向量索引
 * 的存储诉求完全不同——前者要事务与关系约束，后者要向量类型与 ANN 索引。
 * 用 MySQL 存业务、用 PostgreSQL 存向量是本项目 ADR 里定下的分工，
 * 因此这里必须显式建立第二个 DataSource，并给它配一套<b>独立的 Flyway</b>
 * （迁移脚本目录 {@code classpath:db/vector-migration}）。
 *
 * <p>两套 Flyway 的意义：项目仍然拥有全部 DDL（与 H2/MySQL 的处理一致），
 * 而不是让框架去自动建向量表——那样"H2 测试通过、真库结构不对"的问题会再次出现。
 *
 * <p>注意：这个配置类整体由 {@code nexus.vector.enabled} 控制。
 * standalone/测试环境没有 PostgreSQL 时会被关闭，
 * 由 {@code DisabledVectorStore} 提供"明确报错"的降级实现（而不是静默不干活）。
 */
@Configuration
@ConditionalOnProperty(name = "nexus.vector.enabled", havingValue = "true")
@EnableConfigurationProperties(VectorDataSourceConfig.VectorDataSourceProperties.class)
public class VectorDataSourceConfig {

    /**
     * 向量库连接参数。
     *
     * <p>刻意与业务数据源分开配置（前缀 {@code nexus.vector.datasource}），
     * 而不是复用 {@code spring.datasource}：两者可能指向完全不同的实例与账号。
     */
    @ConfigurationProperties("nexus.vector.datasource")
    public record VectorDataSourceProperties(String url, String username, String password) {
    }

    @Bean(name = "vectorDataSource")
    public DataSource vectorDataSource(VectorDataSourceProperties properties) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(properties.url())
                .username(properties.username())
                .password(properties.password())
                .build();
        // 向量库只承担向量读写，连接数不需要多；本机内存紧张，池子开小一点
        dataSource.setMaximumPoolSize(5);
        dataSource.setPoolName("nexus-vector-pool");
        return dataSource;
    }

    @Bean(name = "vectorFlyway")
    public Flyway vectorFlyway(@Qualifier("vectorDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/vector-migration")
                .baselineOnMigrate(true)
                .load();
    }

    /**
     * 让自定义的 Flyway 实例在启动时真正执行迁移。
     *
     * <p>Spring Boot 只会自动触发"它自己创建的那个 Flyway"，手工声明的 Flyway bean
     * 不会自动 migrate——必须用 {@link FlywayMigrationInitializer} 显式驱动，
     * 否则表现是"应用起来了，但向量表根本不存在"。
     */
    @Bean
    public FlywayMigrationInitializer vectorFlywayInitializer(
            @Qualifier("vectorFlyway") Flyway flyway) {
        return new FlywayMigrationInitializer(flyway);
    }

    @Bean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
