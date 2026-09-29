package com.lky.prisma.app.auth.config;

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
 * JWT 配置（{@code prisma.jwt.*}）。
 *
 * <p>密钥<b>绝不写默认值</b>：{@code prisma.jwt.secret} 缺失时应用直接启动失败，
 * 而不是悄悄使用一个弱默认密钥。理由：默认密钥一旦被带进生产，
 * 任何人都能伪造出合法 Token（这类事故在真实项目里并不罕见）。
 * 配置方式见项目根目录 `.env.example` 的 {@code JWT_SECRET}。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "prisma.jwt")
public class PrismaJwtProperties {

    /** HMAC-SHA256 签名密钥，UTF-8 编码后至少 32 字节。 */
    @NotBlank(message = "prisma.jwt.secret（环境变量 JWT_SECRET）必须配置")
    private String secret;

    /** 签发者标识，写进 iss 声明。 */
    @NotBlank
    private String issuer = "prisma";

    /** 访问令牌有效期。 */
    @NotNull
    private Duration accessTokenTtl = Duration.ofMinutes(30);

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
                    "prisma.jwt.secret 至少需要 32 字节（HS256 要求），当前仅 " + keyBytes.length + " 字节");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
