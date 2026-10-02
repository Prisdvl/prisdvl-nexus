package com.lky.nexus.app.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 安全模块存储的装配：按 {@code nexus.security.redis-enabled} 二选一。
 *
 * <p>两个 bean 各自用 {@code @ConditionalOnProperty} 明确条件，而不是一个
 * {@code @ConditionalOnMissingBean}——后者在同一个配置类里的求值顺序依赖 bean 注册顺序，
 * 是那种"本地跑得好、换台机器就随到另一个实现"的隐式行为。用互斥的显式条件，读代码就能确定结果。
 *
 * <p>注意加了 {@code spring-boot-starter-data-redis} 之后，
 * {@code RedisAutoConfiguration} 会**无条件**提供 {@code StringRedisTemplate} bean
 * （Lettuce 是懒连接，不建连、不报错）。也就是说 Redis 关掉的 profile 里这个 bean 也在，
 * 只是没人用它。真正需要额外处理的是健康检查：Redis 指示器会去连一次，
 * 于是 {@code standalone}/{@code test} 必须显式
 * {@code management.health.redis.enabled=false}，否则 {@code /actuator/health} 变 DOWN。
 */
@Configuration
@EnableConfigurationProperties(NexusSecurityProperties.class)
public class SecurityStoreConfiguration {

    /** 跨实例共享（真机栈 / 多实例）。 */
    @Bean
    @ConditionalOnProperty(prefix = "nexus.security", name = "redis-enabled", havingValue = "true")
    public SecurityStore redisSecurityStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisSecurityStore(stringRedisTemplate);
    }

    /** 单实例内存实现（{@code standalone} / {@code test}，也是缺省）。 */
    @Bean
    @ConditionalOnProperty(prefix = "nexus.security", name = "redis-enabled",
            havingValue = "false", matchIfMissing = true)
    public SecurityStore inMemorySecurityStore() {
        return new InMemorySecurityStore();
    }
}
