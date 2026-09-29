package com.lky.nexus.app.infra.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * standalone 开发 profile 专用：为 H2 控制台放行。
 *
 * <p>{@code @Profile("standalone")} 保证它<b>只</b>在本地开发 profile 生效，
 * 生产环境根本不会加载这个 Bean——比在生产配置里放行 {@code /h2-console/**} 安全得多。
 * （H2 控制台能直接执行 SQL，暴露到公网等于把数据库交出去。）
 *
 * <p>必须同时关闭 CSRF 并用 {@code sameOrigin} 允许 iframe：H2 控制台是表单 + frame 结构，
 * 默认的安全头会直接把它挡死，表现为"打开是一片空白"。
 */
@Configuration
@Profile("standalone")
public class StandaloneDevSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/h2-console/**")
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
