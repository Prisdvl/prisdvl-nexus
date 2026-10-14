package com.lky.nexus.app.auth.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenHasherTest {

    @Test
    @DisplayName("同一明文得到稳定 64 位十六进制 SHA-256")
    void hashIsStableHexSha256() {
        String token = "opaque-refresh-sample";
        String a = RefreshTokenHasher.hash(token);
        String b = RefreshTokenHasher.hash(token);
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a).isNotEqualTo(token);
    }

    @Test
    @DisplayName("空明文拒绝")
    void blankRejected() {
        assertThatThrownBy(() -> RefreshTokenHasher.hash("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
