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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT {@code iss}（签发者）校验（第六优先级）。
 *
 * <p><b>为什么要有这个类</b>：{@code iss} 此前是<b>只写不验</b>——{@code TokenService}
 * 签发时把它写进声明，校验侧却从来没有比对过。它单独并不构成能被利用的缺口
 * （对称密钥已经决定了只有本应用签得出令牌），但它是一类更值得防的信号：
 * <b>一个写了却从不验证的声明，会让后来的人以为这条链路已经校验过 iss</b>。
 * 当将来出现第二个签发方、或别的组件共用同一个密钥时，这一条就是唯一能区分"谁签的"的东西，
 * 而那时再发现它其实一直没生效，代价要大得多。
 *
 * <p><b>本类的关键在于"对照"</b>：仅仅断言"错误 iss → 401"是不够的——
 * 401 也可能来自我这支手工令牌的别的地方（比如漏了 {@code ver}、或 {@code sub} 指向的用户不存在）。
 * 那种情况下用例仍然会绿，但它证明的是别的东西，属于本仓库明确要避开的"空断言"。
 * 所以这里用同一套签发参数造三支令牌，<b>只有 iss 不同</b>，逐一对照。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("JWT：iss 声明被真正校验（此前只写不验）")
class JwtIssuerValidationTest {

    private static final String PASSWORD = "Iss!Valid8Pwd";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private NexusJwtProperties properties;

    @Test
    @DisplayName("★ 同一套声明、只有 iss 不同：正确的放行，错误的与缺失的一律 401")
    void onlyTheIssuerDiffersSoTheRefusalIsAttributableToIss() throws Exception {
        String[] registered = registerAndLogin("iss-validation");
        String accessToken = registered[0];
        String userId = registered[1];

        // 对照组 0：应用自己签发的令牌（真实登录拿到的那支）——必须正常
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("iss-validation"));

        // 对照组 1：手工签发，iss 与配置一致 —— 必须放行。
        // 这一支存在的意义是把"手工签发"这个变量固定住：它和我下面那两支走的是
        // 同一段代码、同样的 ver/sub/exp，唯一区别就是 iss
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + mint(userId, properties.getIssuer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("iss-validation"));

        // 换了个签发者：拒绝
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + mint(userId, "someone-else")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        // 压根不带 iss：同样拒绝（JwtIssuerValidator 与一个 null 的签发者比较，必然不等）。
        // 这一条是"缺失"与"错误"两种形态，别只测一种——只测错误的话，
        // "忘了写 iss"的实现照样能通过
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + mint(userId, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    // ------------------------------------------------------------------

    /**
     * 用应用自己的编码器手工签一支令牌。
     *
     * <p>{@code issuer == null} 时不写 iss 声明——这就是"缺失"那一形态。
     * 其余声明（sub/ver/roles/exp）都与真实令牌一致，因此被拒的原因只可能是 iss。
     */
    private String mint(String userId, String issuer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .subject(userId)
                .claim("username", "iss-validation")
                .claim("roles", List.of("USER"))
                .claim("ver", 0L);
        if (issuer != null) {
            claims.issuer(issuer);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    /** @return {@code [accessToken, userId]} */
    private String[] registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return new String[]{
                JsonPath.read(body, "$.data.accessToken"),
                // id 是 Long，按 ADR-017 全局序列化成字符串，这里读到的就是字符串
                JsonPath.read(body, "$.data.user.id")};
    }
}
