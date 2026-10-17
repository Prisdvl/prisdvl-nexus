package com.lky.nexus.app.agent.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计脱敏与截断（P0 §九）。
 *
 * <p>为什么脱敏必须单独测一轮：它的失败方式是<b>静默的</b>——密钥原样写进审计表，
 * 当时没有任何报错，只有事后翻备份的人才会看见。所以这里把几种真实形态
 * （JSON 键值、Bearer 头、厂商密钥）逐一钉住，而不是只看"输出里没有那个词"。
 *
 * <p>刻意的取舍：宁可多打几个星号也不放过凭据。因此"含有 token 这个词"这种保守判定
 * 是被允许的误伤，测试也只断言"不泄露原值"，不去追求保留尽可能多的原文。
 */
@DisplayName("AgentTraceGuards：敏感字段脱敏与截断")
class AgentTraceGuardsTest {

    @Test
    @DisplayName("JSON 键值：apiKey / token / password 的值一律替换成 ***")
    void masksSensitiveJsonValues() {
        String raw = """
                {"apiKey":"sk-abcdefghijklmnop123456","note":"保留","password":"hunter2"}""";

        String masked = AgentTraceGuards.mask(raw);

        assertThat(masked).doesNotContain("sk-abcdefghijklmnop123456", "hunter2");
        assertThat(masked).contains("\"apiKey\":\"***\"", "\"password\":\"***\"");
        // 非敏感字段照原样保留：脱敏不该把可排查的信息一起抹掉
        assertThat(masked).contains("保留");
    }

    @Test
    @DisplayName("Bearer 令牌与 sk- 前缀密钥：即使不在 JSON 里也要脱敏")
    void masksBareTokens() {
        String raw = "Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456";

        assertThat(AgentTraceGuards.mask(raw))
                .doesNotContain("abcdefghijklmnopqrstuvwxyz123456")
                .contains("Bearer ***");
        assertThat(AgentTraceGuards.mask("token=sk-abcdefghijklmnop123456"))
                .doesNotContain("sk-abcdefghijklmnop123456")
                .contains("sk-***");
    }

    @Test
    @DisplayName("digest：先脱敏再截断，超长内容带截断标记")
    void digestTruncates() {
        String long_ = "x".repeat(50);

        assertThat(AgentTraceGuards.digest(long_, 10)).hasSizeLessThanOrEqualTo(10 + "…(截断)".length());
        assertThat(AgentTraceGuards.digest(long_, 10)).startsWith("xxxxxxxxxx").contains("截断");
        assertThat(AgentTraceGuards.digest("short", 10)).isEqualTo("short");
    }

    @Test
    @DisplayName("null 与空串是安全的：轨迹字段允许为空，不能因为脱敏而抛异常")
    void nullSafe() {
        assertThat(AgentTraceGuards.digest(null)).isNull();
        assertThat(AgentTraceGuards.mask(null)).isNull();
        assertThat(AgentTraceGuards.mask("")).isEmpty();
    }
}
