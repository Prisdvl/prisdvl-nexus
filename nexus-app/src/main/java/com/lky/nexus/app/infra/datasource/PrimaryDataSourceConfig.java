package com.lky.nexus.app.infra.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 主数据源（业务库，MySQL）与它的 Flyway 的显式声明。
 *
 * <p><b>为什么需要这个类</b>（两个真实踩坑，都是"Boot 悄悄退让"）：
 * <ol>
 *   <li>业务数据源：{@code DataSourceAutoConfiguration} 的内层带
 *       {@code @ConditionalOnMissingBean(DataSource.class)}，应用里一旦出现<b>任何</b>
 *       DataSource bean（本项目为向量库新增了 PostgreSQL 数据源），它就不再创建主数据源——
 *       表现是"MySQL 连接池根本没建、应用连到了错误的数据源上"；</li>
 *   <li>业务库迁移：{@code FlywayAutoConfiguration} 同样是
 *       {@code @ConditionalOnMissingBean(Flyway.class)}。我声明了向量库的 Flyway 之后，
 *       <b>MySQL 的迁移从此再没执行过</b>——而且没有任何报错，只是新迁移静默不生效
 *       （表现是"表不存在"，排查方向很容易跑偏到 SQL 上）。</li>
 * </ol>
 * 结论：多数据源场景下，主数据源与主库迁移都必须显式声明，不能依赖自动配置。
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /** 业务库（MySQL）的迁移，脚本目录 {@code classpath:db/migration}。 */
    @Bean(name = "primaryFlyway")
    @Primary
    public Flyway primaryFlyway(javax.sql.DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }

    @Bean
    public FlywayMigrationInitializer primaryFlywayInitializer(Flyway primaryFlyway) {
        return new FlywayMigrationInitializer(primaryFlyway);
    }
}

