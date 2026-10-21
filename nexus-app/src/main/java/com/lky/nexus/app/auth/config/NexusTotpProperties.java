package com.lky.nexus.app.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 两步验证（TOTP）配置（{@code nexus.auth.totp.*}）。
 *
 * <p>由 {@link TotpConfiguration} 显式 {@code @EnableConfigurationProperties} 登记
 * （项目不用 {@code @ConfigurationPropertiesScan}）。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "nexus.auth.totp")
public class NexusTotpProperties {

    /**
     * 写进 otpauth URI 与验证器里显示的服务名。
     *
     * <p>它不是密钥，但必须是<b>同一实例内稳定</b>的值：换掉之后用户手机上那条条目
     * 会跟着改名，看着像"多了一个新账号"。
     */
    @NotBlank
    private String issuer = "Prisdvl Nexus";

    /** 验证码位数。RFC 6238 与主流验证器都用 6 位；8 位只是被允许，不是默认。 */
    @Min(6)
    private int digits = 6;

    /** 时间步长（秒）。30 秒是 RFC 6238 的推荐值，所有验证器 App 都默认它。 */
    @Min(15)
    private int periodSeconds = 30;

    /**
     * 允许的时间步漂移（前后各多少个 period）。
     *
     * <p>1 = 容忍约 ±30 秒的时钟偏差。不能放大：每多一步，猜中一枚 6 位码的概率就多 1/10^6。
     */
    @Min(0)
    private int window = 1;

    /** 一次生成多少枚恢复码。10 枚足够覆盖"换手机 + 丢备份"的现实场景。 */
    @Min(1)
    private int recoveryCodes = 10;

    @NotNull
    private java.time.Duration setupTtl = java.time.Duration.ofMinutes(15);
}
