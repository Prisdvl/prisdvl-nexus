package com.lky.nexus.app.tools.cleanup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 清理工具的安全语义纯单元测试（不需要数据库、不需要 Spring 上下文）。
 *
 * <p><b>为什么这些断言值得单独存在</b>：清理工具是唯一能"越过账号体系"直接删数据的通道，
 * 它最大的风险不是删不掉，而是<b>删错</b>。而"哪些账号会被删"这件事完全由前缀白名单与保护名单决定，
 * 是一段纯逻辑——那就必须能在不连数据库的情况下被钉住。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>保护账号永不被匹配</b>：{@code Prisdvl}（真实数据）与 {@code demo}（演示数据）；</li>
 *   <li><b>默认前缀必须覆盖全部已知残留账号</b>：下面的名单是 2026-10-03 实测的
 *       78 个残留账号，逐字写进来当回归数据——默认配置改动若漏掉某种命名约定，这里立刻红；</li>
 *   <li><b>保护账号被命中时必须中止</b>（fail-closed），而不是静默跳过。</li>
 * </ol>
 */
class CleanupPropertiesTest {

    /** 2026-10-03 实测的 78 个残留测试账号（逐字取自 dry-run 输出，作为回归数据固定下来）。 */
    private static final List<String> KNOWN_TEST_ACCOUNTS = List.of(
            "smoke_130761", "kbtest_74083", "kbtest_4624", "vault_48723",
            "sync_65094", "sync2_60077", "async_37490", "uicheck_22108",
            "nokb_probe", "policy-check-101",
            "probe-nokb-mup1wtny", "probe-nokb-mup1xn2d", "probe-ask-mup1xv98",
            "probe-ask-mup220bw", "probe-ask-mup22ml7", "probe-nokb-mup23bfr",
            "probe-nokb-mup2485z", "probe-nokb-mup259gi", "probe-nokb-mup2696v",
            "mcp-live-a", "mcp-live-b",
            "err5-2-1790834181", "err52-1790834194", "err52-1790834199",
            "err52b-1790834852", "err52b-1790834873",
            "upload-limit-1790834888", "keyok-1790835033",
            "probe-nokb-muqbwoey", "multi-kb-other-1790913055974",
            "probe-nokb-muqffj7h", "sec-check-muqv86tv", "probe-nokb-muqvcl7m",
            "multi-kb-other-1790940016591", "probe-nokb-muqwkdcz",
            "multi-kb-other-1790941915030", "probe-diag-1", "probe-nokb-muqwtqb9",
            "sec-check-muqwu1vc", "multi-kb-other-1790942356968", "probe-nokb-muqx0vzc",
            "sec-check-muqx1b4y", "multi-kb-other-1790942695634",
            "multi-kb-other-1790942952287", "smoke-1790945062555",
            "smoke-other-1790945062555", "multi-kb-other-1790945780736",
            "probe-nokb-muqyx07z", "sec-check-muqyx7ts", "smoke-1790945864214",
            "smoke-other-1790945864214", "probe-nokb-murs3j6i",
            "smoke-1790994921927", "smoke-other-1790994921927", "sec-check-murs770n",
            "multi-kb-other-1790995186535", "probe-nokb-murtywup",
            "smoke-1790998042958", "smoke-other-1790998042958",
            "multi-kb-other-1790998074706", "sec-check-muru0zyw",
            "ua-1791005312782", "ua-1791005373498", "smoke-1791005580144",
            "smoke-other-1791005580144", "fmt-1791005626388", "fmt-1791005669576",
            "fmt-1791005715690", "ua-1791006721477", "multi-kb-other-1791006892110",
            "ua-1791007664123", "ua-1791007792460", "smoke-1791007938680",
            "smoke-other-1791007938680", "fmt-1791007948241",
            "multi-kb-other-1791007969716", "sec-check-mus063km", "probe-nokb-mus0691v");

    @Test
    @DisplayName("默认前缀必须覆盖全部 78 个已知残留账号（少覆盖一种命名约定就会红）")
    void defaultsCoverEveryKnownTestAccount() {
        CleanupProperties properties = new CleanupProperties();

        assertThat(KNOWN_TEST_ACCOUNTS)
                .as("回归数据本身应当是 78 个账号")
                .hasSize(78);

        List<String> missed = KNOWN_TEST_ACCOUNTS.stream()
                .filter(username -> !properties.matches(username))
                .toList();

        assertThat(missed)
                .as("默认前缀覆盖不到的残留账号（说明前缀白名单漏了命名约定）")
                .isEmpty();
    }

    @Test
    @DisplayName("真实账号 Prisdvl 与演示账号 demo 绝不被匹配")
    void protectedAccountsAreNeverMatched() {
        CleanupProperties properties = new CleanupProperties();

        assertThat(properties.getProtectedUsernames())
                .as("硬保护名单")
                .containsExactlyInAnyOrder("Prisdvl", "demo");

        assertThat(properties.matches("Prisdvl")).isFalse();
        assertThat(properties.matches("demo")).isFalse();
        // 默认配置下守卫不应抛异常
        properties.assertProtectedNotMatched();
    }

    @Test
    @DisplayName("保护账号被前缀命中时中止（fail-closed），而不是跳过它继续删别人")
    void abortsWhenProtectedAccountIsMatched() {
        CleanupProperties properties = new CleanupProperties();
        properties.setUsernamePrefixes(List.of("Pris"));

        assertThatThrownBy(properties::assertProtectedNotMatched)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Prisdvl")
                .hasMessageContaining("已中止");
    }

    @Test
    @DisplayName("空前缀被忽略：空串匹配一切，若生效等于把作用域放大到全库")
    void emptyPrefixIsIgnored() {
        CleanupProperties properties = new CleanupProperties();
        properties.setUsernamePrefixes(List.of(""));

        assertThat(properties.matches("Prisdvl")).isFalse();
        assertThat(properties.matches("anything")).isFalse();
        // 关键：空前缀生效的话，保护账号会被"匹配"，守卫就应该炸——这里断言它不炸，
        // 即证明空串确实被跳过了。
        properties.assertProtectedNotMatched();
    }

    @Test
    @DisplayName("不命中的账号一律不动：作用域是白名单，不是黑名单")
    void unrelatedAccountsAreNotMatched() {
        CleanupProperties properties = new CleanupProperties();

        // 将来可能出现的第三方真实账号命名
        assertThat(properties.matches("zhangsan")).isFalse();
        assertThat(properties.matches("neo")).isFalse();
        assertThat(properties.matches(null)).isFalse();
    }

    @Test
    @DisplayName("默认是 dry-run，且会连带清理账号名下的内置 Agent")
    void defaultsAreSafe() {
        CleanupProperties properties = new CleanupProperties();

        assertThat(properties.isExecute())
                .as("默认必须只读：真删要显式打开")
                .isFalse();
        assertThat(properties.isPurgeBuiltinAgents()).isTrue();
    }
}
