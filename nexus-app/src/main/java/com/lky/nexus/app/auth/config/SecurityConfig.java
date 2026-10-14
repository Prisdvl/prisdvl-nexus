package com.lky.nexus.app.auth.config;

import com.lky.nexus.app.auth.security.RestAccessDeniedHandler;
import com.lky.nexus.app.auth.security.RestAuthenticationEntryPoint;
import com.lky.nexus.app.auth.security.TokenVersionValidator;
import com.lky.nexus.app.security.RateLimitQuotaFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
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
 *   <li><b>权限来源是 Token 里的 roles 声明</b>，不是数据库查询；
 *       但 5.6 起每个请求会多校验一次"令牌版本"（改密码即刻失效），
 *       正常情况下读 Redis 缓存、故障时回落查库 —— 这是 ADR-018 第 4 条
 *       "不在每个请求上查库"的一处**有意例外**，理由与代价见 ADR-020；</li>
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
     * 见 docs/decisions.md B.42 与 docs/archive/reports/productization-gap-analysis.md P0-1。
     */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/login",
            // refresh / logout 凭 body 中的 opaque refreshToken 鉴权；access 可能已过期
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/password/reset/request",
            "/api/auth/password/reset/confirm"
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
                                                      JwtAuthenticationConverter jwtAuthenticationConverter,
                                                      RateLimitQuotaFilter rateLimitQuotaFilter)
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
                        .accessDeniedHandler(accessDeniedHandler))
                // 限流/配额（5.6）：必须挂在 BearerTokenAuthenticationFilter 之后
                // ——那时 SecurityContext 已填充（CurrentUser 可用），
                // 又早于任何控制器（所以 SSE 还没开流，超限时回的是统一 JSON）。
                .addFilterAfter(rateLimitQuotaFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 阻止 {@link RateLimitQuotaFilter} 被 Servlet 容器**再注册一次**。
     *
     * <p>这是个很容易踩、后果又很具体的坑：Spring Boot 会把容器里任何 {@code Filter} bean
     * 自动注册到 Servlet 过滤器链上。而上面又把它显式加进了 Security 链——
     * 于是同一个请求它会跑两遍。
     *
     * <p>对 RequestIdFilter 那种幂等过滤器，跑两遍只是浪费；
     * 但限流器**不幂等**：一次请求自增两次，用户看到的限额直接砍半、
     * 配额也双倍消耗。所以这里显式关掉容器注册，只保留 Security 链里的那一次。
     */
    @Bean
    public FilterRegistrationBean<RateLimitQuotaFilter> rateLimitQuotaFilterRegistration(
            RateLimitQuotaFilter filter) {
        FilterRegistrationBean<RateLimitQuotaFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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

    /**
     * 校验器：与签发器共用同一密钥，限定算法为 HS256（防止算法混淆攻击），
     * 校验签发者（iss），并追加令牌版本校验（5.6：改密码即刻作废旧令牌）。
     *
     * <p><b>为什么必须用 {@link DelegatingOAuth2TokenValidator} 包一层</b>：
     * {@code build()} 已经挂了 {@code JwtValidators.createDefault()}（含 exp / nbf），
     * 而 {@code setJwtValidator} 是<b>替换</b>语义、不是追加——直接换成版本校验器，
     * 过期校验就没了，30 分钟后本该过期的令牌会一直有效。
     * 这是个"看起来只是加一个校验"的改动，代价却是把过期机制整个拆掉，
     * 所以这里显式把三者组合在一起（并有测试盯着"过期令牌仍然被拒"）。
     *
     * <p><b>iss 校验（第六优先级）</b>：此前 {@code iss} 只写不验——签发时写进声明，
     * 校验侧却从没比对过。它单独并不构成可被利用的缺口（对称密钥已经决定了只有本应用
     * 签得出令牌），但"写了一个声明却从不验证"是一类更坏的信号：它让后来的人以为
     * 这条链路已经校验过 iss 了（比如将来换成多服务、或让别的组件共用同一个密钥时，
     * 这一条就是唯一能区分"谁签的"的东西）。补上它，代价是一行，语义却从"看起来有"
     * 变成"真的有"——这正是本项目对"假完整"的一贯口径。
     *
     * <p>用 {@code createDefaultWithIssuer} 而不是再 {@code new JwtIssuerValidator}：
     * 后者要自己记得把 timestamp 一起带上，而这个方法名把"默认校验 + iss"写在了签名里。
     * 它同时覆盖了"令牌压根没有 iss"的情形（{@code getIssuer()} 为 null，与期望值不等 → 拒绝）。
     */
    @Bean
    public JwtDecoder jwtDecoder(NexusJwtProperties properties, TokenVersionValidator tokenVersionValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()), tokenVersionValidator));
        return decoder;
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
