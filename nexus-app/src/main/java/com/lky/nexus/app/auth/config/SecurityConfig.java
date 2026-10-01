package com.lky.nexus.app.auth.config;

import com.lky.nexus.app.auth.security.RestAccessDeniedHandler;
import com.lky.nexus.app.auth.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Spring Security 配置：无状态 JWT 鉴权。
 *
 * <p>关键决策与理由：
 * <ol>
 *   <li><b>STATELESS + 关闭 CSRF</b>：纯 Token API 不用 Cookie/Session，
 *       没有"浏览器自动携带凭证"的问题，因此 CSRF 防护不适用；</li>
 *   <li><b>关闭 formLogin / httpBasic</b>：避免 Spring Security 默认弹出 Basic 认证框、
 *       或返回登录页 HTML，破坏 JSON 契约；</li>
 *   <li><b>401 与 403 都自定义</b>：统一成 {@code {code,message,data}}（见两个 handler 的说明）；</li>
 *   <li><b>权限来源是 Token 里的 roles 声明</b>，不是数据库查询：
 *       无状态鉴权不在每个请求上查库，exp 与吊销由令牌机制控制；</li>
 *   <li><b>@Order(2)</b>：给测试/未来其他过滤器链留出靠前的顺序位置。</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(NexusJwtProperties.class)
public class SecurityConfig {

    /**
     * 公开端点：只有注册与登录。
     *
     * <p><b>{@code /mcp} 曾经也在这里</b>（身份靠 {@code X-Nexus-User} 头自证，注释写着"个人本机信任模型"）。
     * 那是一条把整套隔离绕开的路径：任何人可达该端口即可指定任意 userId，
     * 读走别人的笔记正文、用别人的默认 Agent 跑问答（花他的额度）。
     * 2026-10-01 起与其余端点同一套身份：{@code Authorization: Bearer <JWT>}，
     * 想只在本机用就把服务绑到 127.0.0.1（隔离靠网络，不靠请求头）。
     * 见 docs/decisions.md B.42 与 docs/productization-gap-analysis.md P0-1。
     */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/login"
    };

    /** 健康检查与错误页放行，供 Docker healthcheck、K8s 探针与统一错误处理使用。 */
    private static final String[] PUBLIC_INFRA_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/error"
    };

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      RestAuthenticationEntryPoint authenticationEntryPoint,
                                                      RestAccessDeniedHandler accessDeniedHandler,
                                                      JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(PUBLIC_INFRA_ENDPOINTS).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 兜底：除上面明确放行的，其它一律要求已认证
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /** 密码哈希：BCrypt（自带随机盐、可调强度），绝不存明文、不用 MD5/SHA。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 对称密钥签发器（HS256）。 */
    @Bean
    public JwtEncoder jwtEncoder(NexusJwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretKey()));
    }

    /** 校验器：与签发器共用同一密钥，并限定算法为 HS256（防止算法混淆攻击）。 */
    @Bean
    public JwtDecoder jwtDecoder(NexusJwtProperties properties) {
        return NimbusJwtDecoder.withSecretKey(properties.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * 把 JWT 的 {@code roles} 声明转成 Spring Security 权限，例如 {@code ["USER"]} → {@code ROLE_USER}。
     *
     * <p>前缀 {@code ROLE_} 是 {@code hasRole("USER")} 的约定；
     * 若用 {@code hasAuthority("USER")} 则不需要前缀——两者混用是最常见的 403 排查方向。
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
