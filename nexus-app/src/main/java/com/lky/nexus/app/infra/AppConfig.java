package com.lky.nexus.app.infra;

import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用级 bean 装配（跨层公共组件的装配统一放这里）。
 *
 * <p>{@link ObservabilityRegistry} 在 nexus-common 里是纯 Java 类——
 * common 是零 Spring 依赖的底座，装配是 app 层的事。
 */
@Configuration
public class AppConfig {

    @Bean
    public ObservabilityRegistry observabilityRegistry() {
        return new ObservabilityRegistry();
    }
}