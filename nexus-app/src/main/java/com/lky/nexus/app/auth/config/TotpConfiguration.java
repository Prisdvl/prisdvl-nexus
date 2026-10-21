package com.lky.nexus.app.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 两步验证配置登记。
 *
 * <p>单独一个配置类而不是把 {@code @EnableConfigurationProperties} 挂到 Service 上：
 * 项目里登记处都集中在 {@code *Configuration}（对照 {@code EmailCodeConfiguration}），
 * 好处是"这个前缀被谁读"一眼可见。
 */
@Configuration
@EnableConfigurationProperties(NexusTotpProperties.class)
public class TotpConfiguration {
}
