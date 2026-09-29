package com.lky.nexus.app.infra.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 主数据源（业务库，MySQL）的显式声明。
 *
 * <p><b>为什么需要这个类</b>（真实踩坑）：Boot 的 {@code DataSourceAutoConfiguration}
 * 用 {@code @ConditionalOnMissingBean(DataSource.class)} 决定"要不要自动建主数据源"。
 * 一旦应用里出现了<b>任何</b> DataSource 类型的 bean（本项目为向量库新增了 PostgreSQL 数据源），
 * 它就会整体退让——表现是：<b>MySQL 连接池根本没创建</b>，
 * 而应用要么连到错误的数据源上、要么以各种莫名其妙的缺连接失败。
 *
 * <p>标准解法是把主数据源也显式声明出来，并用 {@code @Primary} 标明它是默认注入目标，
 * 这样"两个数据源"这件事从隐式猜测变成显式契约：
 * <ul>
 *   <li>{@code spring.datasource.*} 与 {@code spring.datasource.hikari.*} 的绑定行为保持不变
 *       （由 Boot 的 {@link DataSourceProperties} 负责，迁移到本项目没有改变配置语义）；</li>
 *   <li>按类型注入 DataSource 的组件（MyBatis、Flyway、DatasourceGuard）拿到的都是业务库，
 *       向量库必须显式 {@code @Qualifier("vectorDataSource")} 才能拿到。</li>
 * </ul>
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
}
