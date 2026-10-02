package com.lky.nexus.app.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT 配置（{@code nexus.jwt.*}）。
 *
 * <p>密钥<b>绝不写默认值</b>：{@code nexus.jwt.secret} 缺失时应用直接启动失败，
 * 而不是悄悄使用一个弱默认密钥。理由：默认密钥一旦被带进生产，
 * 任何人都能伪造出合法 Token（这类事故在真实项目里并不罕见）。
 * 配置方式见项目根目录 `.env.example` 的 {@code JWT_SECRET}。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "nexus.jwt")
public class NexusJwtProperties {

    /** HMAC-SHA256 签名密钥，UTF-8 编码后至少 32 字节。 */
    @NotBlank(message = "nexus.jwt.secret（环境变量 JWT_SECRET）必须配置")
    private String secret;

    /** 签发者标识，写进 iss 声明。 */
    @NotBlank
    private String issuer = "prisdvl-nexus";

    /** 访问令牌有效期。 */
    @NotNull
    private Duration accessTokenTtl = Duration.ofMinutes(30);

    /**
     * "用户当前令牌版本"的缓存 TTL（5.6）。
     *
     * <p>这个值不只是性能旋钮——它同时是**缓存失效失败时旧令牌的存活上限**：
     * 改密码会把版本推上去并主动删缓存，万一删除失败，旧令牌最多再活这么久。
     * 调大省 DB 查询，但把那个窗口一起放大。见 {@code UserAuthCache.invalidate}。
     */
    @NotNull
    private Duration tokenVersionCacheTtl = Duration.ofMinutes(5);

    /**
     * 构造 HMAC 密钥。
     *
     * <p>HS256 要求密钥长度不少于 256 位（32 字节）；太短的密钥会被 Nimbus 拒绝，
     * 这里提前给出可读的错误信息，避免启动时只看到底层加密库的晦涩报错。
     */
    public SecretKey secretKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "nexus.jwt.secret 至少需要 32 字节（HS256 要求），当前仅 " + keyBytes.length + " 字节");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
