package com.lky.nexus.app.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 安全相关配置（{@code nexus.security.*}）：限流、每日配额、登录失败冻结。
 *
 * <p>照 {@code AiModelProperties} / {@code RetrievalDefaults} 的模式——项目不用
 * {@code @ConfigurationPropertiesScan}，而是由 {@link SecurityStoreConfiguration} 显式
 * {@code @EnableConfigurationProperties} 登记。
 *
 * <p><b>哪些是"旋钮"、哪些是"结构"</b>：下面的数字都是旋钮（按部署规模调）。
 * 但<b>路径列表</b>不是旋钮——它们表达的是"哪些入口会真的花钱调模型"这个架构事实，
 * 默认值就是当前全部 chat 模型入口。给它们留配置项是为了不必改代码就能加新入口，
 * 不是为了让人随便删。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "nexus.security")
public class NexusSecurityProperties {

    /**
     * 是否用 Redis 承载限流/配额/令牌版本。
     *
     * <p>默认 {@code false}（内存实现），因为 {@code standalone} 与 {@code test}
     * 两个 profile 明确不依赖外部基础设施。{@code local}（真机栈）置为 true。
     */
    private boolean redisEnabled = false;

    private RateLimit rateLimit = new RateLimit();

    private Quota quota = new Quota();

    private Login login = new Login();

    private Register register = new Register();

    /** 每秒请求数限流（固定窗口，用户维度）。 */
    @Getter
    @Setter
    public static class RateLimit {

        /** 每个用户每分钟允许的模型入口请求数。 */
        private int perMinute = 20;

        /**
         * 只限速、不计入 token 配额的额外路径。
         *
         * <p>改密码接口在这里：不限速的话，拿到一支偷来的 token 就可以把它当
         * "原密码爆破口"用（每次试错都返回可区分的 400）。但它不该计入 chat 额度——
         * 它不花一分钱模型费用。
         */
        private List<String> extraPaths = List.of("/api/users/me/password");
    }

    /** 每用户每日配额（超限 fail-closed）。 */
    @Getter
    @Setter
    public static class Quota {

        /** 每日允许的模型入口请求次数。 */
        private int dailyRequests = 200;

        /** 每日允许消耗的 token 数（prompt + completion）。 */
        private long dailyTokens = 200_000L;

        /**
         * 计入每日配额的路径（Ant 风格）。
         *
         * <p>这些就是当前**全部会真的调用 chat 模型**的入口：
         * 会话问答（含 PLAIN/RAG/AGENT 三模式共用的 {@code /api/chat/stream}）、
         * 知识库问答同步与流式、一次性 Agent 问答、Agent 试跑、评测，以及 MCP。
         */
        private List<String> paths = List.of(
                "/api/chat/**",
                "/api/knowledge-bases/*/ask",
                "/api/knowledge-bases/*/ask/stream",
                "/api/agent/ask",
                "/api/agents/*/test-chat",
                "/api/eval/**",
                "/mcp");
    }

    /**
     * 登录失败冻结（用户名维度，fail-open）。
     *
     * <p>已知代价：按用户名冻结意味着<b>可以定向把别人的账号刷锁</b>（用错密码刷到阈值）。
     * 所以窗口与冻结算得比较短，而不是几小时——宁可让攻击者多试几次，也不要把正常用户
     * 挡在门外十几分钟。见 docs/decisions.md ADR-020。
     */
    @Getter
    @Setter
    public static class Login {

        /** 窗口内允许的连续失败次数，达到即冻结。 */
        private int maxFailures = 5;

        /** 失败计数窗口。 */
        private java.time.Duration window = java.time.Duration.ofMinutes(15);

        /** 冻结时长（从达到阈值那一刻起算）。 */
        private java.time.Duration lockDuration = java.time.Duration.ofMinutes(15);
    }

    /** 注册入口限流（IP 维度）。 */
    @Getter
    @Setter
    public static class Register {

        /** 注册路径。 */
        private String path = "/api/auth/register";

        /** 每 IP 每小时允许的注册次数。 */
        private int perHourPerIp = 10;
    }
}
