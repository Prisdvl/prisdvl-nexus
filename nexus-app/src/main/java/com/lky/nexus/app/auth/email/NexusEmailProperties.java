package com.lky.nexus.app.auth.email;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 邮箱相关配置（{@code nexus.email.*}）。
 *
 * <p>由 {@link EmailCodeConfiguration} 显式 {@code @EnableConfigurationProperties} 登记
 * （项目不用 {@code @ConfigurationPropertiesScan}）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "nexus.email")
public class NexusEmailProperties {

    /**
     * 发送实现：{@code unconfigured}（默认，失败关闭） / {@code console}（仅开发 profile） /
     * {@code smtp}（真实 SMTP） / {@code resend}（Resend HTTP API）。
     * 业务只依赖 {@link EmailSender}。默认不得因新增通道而改变。
     */
    private String sender = "unconfigured";

    private Code code = new Code();

    private Smtp smtp = new Smtp();

    private Resend resend = new Resend();

    @Getter
    @Setter
    public static class Code {

        /** 验证码位数（固定数字）。 */
        private int length = 6;

        /** 验证码存活时间。 */
        private Duration expire = Duration.ofMinutes(5);

        /** 同一验证码允许的错误次数；达到即作废。 */
        private int maxAttempts = 5;

        /** 同一邮箱两次发送的最短间隔。 */
        private Duration cooldown = Duration.ofSeconds(60);

        /**
         * 同一 IP 每小时允许发送验证码的次数。
         * 走 {@link com.lky.nexus.app.security.RateLimiter#checkEmailCodeSend}。
         */
        private int ipPerHour = 30;
    }

    /**
     * SMTP 通道参数（仅 {@code nexus.email.sender=smtp} 时使用）。
     *
     * <p>不复用 {@code spring.mail.*}，避免与 Boot 自动配置双份来源。
     * 凭据只从环境变量 / 外部配置注入，不写进代码或默认值。
     */
    @Getter
    @Setter
    public static class Smtp {

        private String host = "";

        private int port = 587;

        private String username = "";

        private String password = "";

        /** 发件人地址（From）。 */
        private String from = "";

        /** 是否启用 STARTTLS（常用 587）。 */
        private boolean startTls = true;

        @Override
        public String toString() {
            return "Smtp{host='" + host + "', port=" + port
                    + ", username=" + (username == null || username.isBlank() ? "(empty)" : "(set)")
                    + ", password=(redacted), from='" + from + "', startTls=" + startTls + '}';
        }
    }

    /**
     * Resend HTTP API 通道（仅 {@code nexus.email.sender=resend} 时使用）。
     *
     * <p>API Key 只从环境变量 / 外部配置注入（如 {@code RESEND_API_KEY}），
     * 不得写进代码、仓库或日志；{@link #toString()} 不暴露密钥。
     */
    @Getter
    @Setter
    public static class Resend {

        /** Resend API Key（Bearer）；缺失时 Bean 构造失败。 */
        private String apiKey = "";

        /** 发件人地址（From），须为 Resend 已验证域名下的地址。 */
        private String from = "";

        /**
         * API 根地址。默认官方 {@code https://api.resend.com}；
         * 测试可指向 Mock HTTP 服务。
         */
        private String baseUrl = "https://api.resend.com";

        private Duration connectTimeout = Duration.ofSeconds(5);

        private Duration readTimeout = Duration.ofSeconds(15);

        @Override
        public String toString() {
            return "Resend{apiKey=" + (apiKey == null || apiKey.isBlank() ? "(empty)" : "(redacted)")
                    + ", from='" + from + "', baseUrl='" + baseUrl
                    + "', connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout + '}';
        }
    }
}
