package com.lky.nexus.app.security;

import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字段级加密的纯单元测试（不起 Spring 上下文）。
 *
 * <p>为什么这几条必须钉住：加密的失败方式是<b>静默</b>的——写成明文当时不报错，
 * 只有翻库/翻备份的人才看得见。所以这里把「可逆」「换 KEK 解不开」「未配置时拒绝加密」
 * 「超长拒绝」各自单独断言，而不是只测一次 round-trip。
 */
@DisplayName("CredentialCipher：落库凭据的字段级加密（AES-GCM）")
class CredentialCipherTest {

    /** 与 application-test.yml 里同一把测试 KEK（32 字节）。 */
    private static final String KEK = "FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0=";

    private static final String PLAIN = "sk-abcdefghijklmnop123456";

    @Test
    @DisplayName("可逆，且同一明文两次加密密文不同（随机 IV，不是 ECB 那种确定性输出）")
    void roundTripUsesFreshIv() {
        CredentialCipher cipher = cipher(KEK);
        String first = cipher.encrypt(PLAIN);
        String second = cipher.encrypt(PLAIN);

        assertThat(first).startsWith(CredentialCipher.VERSION_PREFIX);
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(PLAIN);
        assertThat(cipher.decrypt(second)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("密文不含明文片段；换一把 KEK 或篡改一个字节都解不开（GCM 认证）")
    void rejectsWrongKeyAndTampering() {
        String stored = cipher(KEK).encrypt(PLAIN);
        assertThat(stored).doesNotContain("sk-abc").doesNotContain("abcdefghijklmnop");

        assertThatThrownBy(() -> cipher(otherKek()).decrypt(stored))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解密失败");

        // 改 payload 里的一个字节（落在 IV 上）：GCM 的 tag 校验必然拒绝
        int at = CredentialCipher.VERSION_PREFIX.length() + 10;
        char swapped = stored.charAt(at) == 'A' ? 'B' : 'A';
        String tampered = stored.substring(0, at) + swapped + stored.substring(at + 1);
        assertThatThrownBy(() -> cipher(KEK).decrypt(tampered))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("未配置 KEK：加密 fail-closed 报错（而不是退化成存明文）")
    void missingKekFailsClosedOnEncrypt() {
        CredentialCipher cipher = cipher("");

        assertThatThrownBy(() -> cipher.encrypt(PLAIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NEXUS_SECURITY_CRYPTO_KEK");
    }

    @Test
    @DisplayName("加密前写下的历史明文：按原样读出，不被误当密文")
    void legacyPlaintextPassesThrough() {
        CredentialCipher cipher = cipher("");
        assertThat(cipher.isEncrypted("sk-legacy-plain")).isFalse();
        assertThat(cipher.decrypt("sk-legacy-plain")).isEqualTo("sk-legacy-plain");
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("  ")).isNull();
    }

    @Test
    @DisplayName("明文上限 320：边界内密文塞得进 VARCHAR(500)，越界直接拒绝")
    void enforcesPlaintextLimit() {
        CredentialCipher cipher = cipher(KEK);
        String max = "k".repeat(CredentialCipher.MAX_PLAINTEXT_LENGTH);

        String stored = cipher.encrypt(max);
        assertThat(stored).hasSizeLessThanOrEqualTo(500);
        assertThat(cipher.decrypt(stored)).isEqualTo(max);

        assertThatThrownBy(() -> cipher.encrypt(max + "x"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("KEK 配错（非法 base64 / 字节数不对）→ 构造即失败，不写进解不开的密文")
    void invalidKekFailsFast() {
        assertThatThrownBy(() -> cipher("not-base64!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");

        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[8])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16/24/32");
    }

    private static CredentialCipher cipher(String kek) {
        NexusSecurityProperties properties = new NexusSecurityProperties();
        properties.getCrypto().setKek(kek);
        return new CredentialCipher(properties);
    }

    private static String otherKek() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 3 + 1);
        }
        return Base64.getEncoder().encodeToString(raw);
    }
}
