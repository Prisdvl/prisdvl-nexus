package com.lky.nexus.app.auth;

import com.lky.nexus.app.auth.totp.TotpAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOTP 算法层测试。
 *
 * <p>用 RFC 6238 附录 B 的<b>官方测试向量</b>：种子是 ASCII 的 {@code "12345678901234567890"}
 * （Base32 为 {@code GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ}）。自己造期望值等于"用被测实现验证被测实现"，
 * 换一个实现（比如改成 HmacSHA256）时会一起错，测不出来。
 */
class TotpAlgorithmTest {

    /** RFC 6238 附录 B 的种子（HMAC-SHA1 列）。 */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    @DisplayName("RFC 6238 官方向量：8 位码在 6 个时间点上逐字匹配")
    void matchesRfc6238Vectors() {
        // (epochSeconds, 期望的 8 位码)
        long[][] vectors = {
                {59L, 94_287_082L},
                {1_111_111_109L, 7_081_804L},
                {1_111_111_111L, 14_050_471L},
                {1_234_567_890L, 89_005_924L},
                {2_000_000_000L, 69_279_037L},
                {20_000_000_000L, 65_353_130L},
        };
        for (long[] vector : vectors) {
            Instant at = Instant.ofEpochSecond(vector[0]);
            long step = TotpAlgorithm.timeStep(at, 30);
            String expected = String.format("%08d", vector[1]);
            assertThat(TotpAlgorithm.code(RFC_SECRET, step, 8))
                    .as("RFC 6238 向量 T=%d", vector[0])
                    .isEqualTo(expected);
            assertThat(TotpAlgorithm.verify(RFC_SECRET, expected, at, 30, 8, 0))
                    .as("同一枚码必须被 verify 接受 T=%d", vector[0])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("window 只放行相邻时间步，超出即拒")
    void verifyHonorsWindow() {
        Instant at = Instant.ofEpochSecond(1_234_567_890L);
        String previous = TotpAlgorithm.code(RFC_SECRET, TotpAlgorithm.timeStep(at, 30) - 1, 6);
        String far = TotpAlgorithm.code(RFC_SECRET, TotpAlgorithm.timeStep(at, 30) - 2, 6);

        assertThat(TotpAlgorithm.verify(RFC_SECRET, previous, at, 30, 6, 1))
                .as("window=1 必须接受上一个时间步（时钟漂移）").isTrue();
        assertThat(TotpAlgorithm.verify(RFC_SECRET, far, at, 30, 6, 1))
                .as("window=1 不能接受两个步长之前").isFalse();
        assertThat(TotpAlgorithm.verify(RFC_SECRET, previous, at, 30, 6, 0))
                .as("window=0 只接受当前步").isFalse();
    }

    @Test
    @DisplayName("非数字、位数不对、空密钥一律返回 false，不抛异常")
    void rejectsMalformedInput() {
        Instant at = Instant.ofEpochSecond(1_234_567_890L);
        assertThat(TotpAlgorithm.verify(RFC_SECRET, "abcdef", at, 30, 6, 1)).isFalse();
        assertThat(TotpAlgorithm.verify(RFC_SECRET, "12345", at, 30, 6, 1)).isFalse();
        assertThat(TotpAlgorithm.verify(RFC_SECRET, null, at, 30, 6, 1)).isFalse();
        assertThat(TotpAlgorithm.verify(null, "123456", at, 30, 6, 1)).isFalse();
    }

    @Test
    @DisplayName("Base32 编解码可逆，随机密钥 20 字节 / 32 字符")
    void base32RoundTrips() {
        String secret = TotpAlgorithm.randomSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(TotpAlgorithm.base32Decode(secret)).hasSize(TotpAlgorithm.DEFAULT_SECRET_BYTES);
        assertThat(TotpAlgorithm.base32Encode(TotpAlgorithm.base32Decode(secret))).isEqualTo(secret);
    }

    @Test
    @DisplayName("otpauth URI 带上 issuer/账号/密钥/位数/周期，且账号与 issuer 里的空格被编码")
    void buildsOtpauthUri() {
        String uri = TotpAlgorithm.otpauthUri("Prisdvl Nexus", "lky", RFC_SECRET, 6, 30);
        assertThat(uri)
                .startsWith("otpauth://totp/")
                .contains("secret=" + RFC_SECRET)
                .contains("issuer=Prisdvl%20Nexus")
                .contains("digits=6")
                .contains("period=30")
                .contains("algorithm=SHA1");
    }
}
