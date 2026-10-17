package com.lky.nexus.app.auth;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.config.NexusJwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5.6 验收测试：修改密码 + 令牌吊销。
 *
 * <p>覆盖点（前两条是这一期真正的价值所在）：
 * <ol>
 *   <li><b>改密后旧 Token 立刻失效</b>——不是文档上的承诺，是拿旧 Token 真打一次拿到 401；</li>
 *   <li><b>过期 Token 仍然被拒</b>——这条是防回归的：把版本校验器挂上去时若直接
 *       {@code setJwtValidator(版本校验)} 而不是组合 {@code JwtValidators.createDefault()}，
 *       过期校验会被静默丢掉，签名与版本都合法的旧 Token 就永久有效了。
 *       这里显式签一支"签名正确、版本正确、只差过期"的 Token 来钉住它；</li>
 *   <li>原密码错误 → <b>400 + 1001</b>，且明确断言<b>不是 401</b>（401 会让前端清 Token 跳登录页，
 *       把输入错误升级成会话中断）；</li>
 *   <li>新密码过短 / 与原密码相同 → 1001；</li>
 *   <li>不带 Token → 401。</li>
 * </ol>
 *
 * <p>全部跑在 H2 + 内存 {@code SecurityStore} 上，不依赖 Docker / Redis。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordChangeFlowTest {

    private static final String OLD_PASSWORD = "OldPassw0rd!23";
    private static final String NEW_PASSWORD = "NewPassw0rd!45";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private NexusJwtProperties jwtProperties;

    @Test
    @DisplayName("★ 改密后旧 Token 立刻 401/2001，用新密码登录拿到的 Token 才能用")
    void changingPasswordRevokesOldTokensImmediately() throws Exception {
        register("pwd-revoke");
        String oldToken = loginToken("pwd-revoke", OLD_PASSWORD);

        // 改密前：旧 Token 可用（顺带证明令牌本来确实是有效的，不是一开始就坏）
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isOk());

        changePassword(oldToken, OLD_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        // 旧密码不再能登录，新密码可以
        login("pwd-revoke", OLD_PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(2006));
        String newToken = loginToken("pwd-revoke", NEW_PASSWORD);
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 过期 Token 仍然被拒：挂版本校验器不能把 exp 校验挤掉")
    void expiredTokenIsStillRejected() throws Exception {
        register("pwd-expired");
        String loginBody = login("pwd-expired", OLD_PASSWORD).andReturn().getResponse().getContentAsString();
        String userId = JsonPath.read(loginBody, "$.data.user.id").toString();

        // 手工签一支"签名正确、ver 正确（0 = 新用户）、但已过期"的令牌。
        // expiresAt 往前推 1 小时：解码器有 60 秒时钟偏移容忍，卡在边界上会变成不确定的测试。
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(jwtProperties.getIssuer())
                        .issuedAt(Instant.now().minusSeconds(7200))
                        .expiresAt(Instant.now().minusSeconds(3600))
                        .subject(userId)
                        .claim("username", "pwd-expired")
                        .claim("roles", List.of("USER"))
                        .claim("ver", 0L)
                        .build())).getTokenValue();

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("★ 原密码错误 → 400 + 1001，且必须是 400 而不是 401（401 会触发前端清 Token 跳登录页）")
    void wrongOldPasswordIsBadRequestNotUnauthorized() throws Exception {
        register("pwd-wrong-old");
        String token = loginToken("pwd-wrong-old", OLD_PASSWORD);

        changePassword(token, "NotMyOldPassword!9", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("原密码不正确"));

        // 改密失败不能有任何副作用：原密码仍然能登录
        login("pwd-wrong-old", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("新密码过短 → 400 + 1001")
    void shortNewPasswordRejected() throws Exception {
        register("pwd-short");
        String token = loginToken("pwd-short", OLD_PASSWORD);

        changePassword(token, OLD_PASSWORD, "1234567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("新密码与原密码相同 → 400 + 1001")
    void samePasswordRejected() throws Exception {
        register("pwd-same");
        String token = loginToken("pwd-same", OLD_PASSWORD);

        changePassword(token, OLD_PASSWORD, OLD_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("不带 Token → 401（改密接口不是公开端点）")
    void anonymousCannotChangePassword() throws Exception {
        mockMvc.perform(put("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"%s"}
                                """.formatted(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("请求体里带 userId 不会改变目标用户（身份只认 JWT）")
    void userIdInBodyIsIgnored() throws Exception {
        register("pwd-victim");
        register("pwd-attacker");
        String victimToken = loginToken("pwd-victim", OLD_PASSWORD);
        String attackerToken = loginToken("pwd-attacker", OLD_PASSWORD);

        // 攻击者构造一个"看起来要改受害者"的请求体：多出来的字段被忽略，改的仍是攻击者自己
        mockMvc.perform(put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + attackerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"oldPassword":"%s","newPassword":"%s"}
                                """.formatted(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk());

        // 受害者完全不受影响
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + victimToken))
                .andExpect(status().isOk());
        login("pwd-victim", OLD_PASSWORD).andExpect(status().isOk());
        // 攻击者自己被改了
        login("pwd-attacker", OLD_PASSWORD).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"%s","email":"%s@example.com"}
                                """.formatted(username, OLD_PASSWORD, username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)));
    }

    private String loginToken(String username, String password) throws Exception {
        String body = login(username, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private ResultActions changePassword(String token, String oldPassword, String newPassword) throws Exception {
        return mockMvc.perform(put("/api/users/me/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"oldPassword":"%s","newPassword":"%s"}
                        """.formatted(oldPassword, newPassword)));
    }
}
