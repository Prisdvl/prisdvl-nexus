package com.lky.nexus.app.auth.token;

import com.lky.nexus.app.auth.config.NexusJwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 访问令牌签发服务（只负责"造令牌"，不负责业务校验）。
 *
 * <p>本项目的 JWT 是<b>自签自发</b>：应用既是授权服务器（签发）又是资源服务器（校验）。
 * 因此用对称密钥 HS256 即可，不需要引入 RSA/EC 密钥对——这是单体应用的合理选择。
 * 将来若拆分服务，把 HS256 换成 RS256（私钥签名、公钥验签）即可让其他服务独立校验。
 *
 * <p>声明（claims）设计：
 * <pre>
 * sub      用户 ID        —— 鉴权后取 userId 的唯一来源，禁止从请求参数里取 userId
 * username 登录名          —— 便于日志与前端展示
 * roles    角色码列表      —— 由 JwtAuthenticationConverter 映射成 ROLE_XXX 权限
 * ver      令牌版本        —— 签发时的 sys_user.token_version，由 TokenVersionValidator 比对；
 *                            改密码 = 版本 +1 = 此前签发的全部令牌同时失效（5.6）
 * jti      令牌唯一 ID     —— 目前仍未使用（预留给"单支令牌吊销"这类未来需求）
 * iss/iat/exp              —— 标准声明
 * </pre>
 *
 * <p><b>为什么版本号能取代 jti 黑名单</b>：黑名单要存每一支被吊销的令牌并按令牌判断成员，
 * 存储是 O(令牌数)；用户级版本号只存一个整数，改一次密码就让该用户所有旧令牌一起失效
 * （"改密码 = 全部设备下线"），存储是 O(用户数)。代价是它在每个请求上多读一次版本
 * （Redis 缓存，故障回落到查库），见 docs/decisions.md ADR-020。
 */
@Service
public class TokenService {

    /** 令牌版本的声明名，与 {@code TokenVersionValidator} 共用同一个约定。 */
    static final String CLAIM_VERSION = "ver";

    private final JwtEncoder jwtEncoder;
    private final NexusJwtProperties properties;

    public TokenService(JwtEncoder jwtEncoder, NexusJwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /**
     * 签发访问令牌。
     *
     * @param userId       用户 ID（写入 sub）
     * @param username     登录名
     * @param roles        角色码，例如 {@code ["USER"]}
     * @param tokenVersion 该用户当前的 {@code sys_user.token_version}（写入 ver）
     */
    public IssuedToken issueAccessToken(Long userId, String username, List<String> roles, long tokenVersion) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim("username", username)
                .claim("roles", roles)
                .claim(CLAIM_VERSION, tokenVersion)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(tokenValue, expiresAt, properties.getAccessTokenTtl().toSeconds());
    }

    /** 签发结果。expiresIn 用 long 基本类型，保证序列化后仍是数字（见 ADR-017）。 */
    public record IssuedToken(String token, Instant expiresAt, long expiresIn) {
    }
}
