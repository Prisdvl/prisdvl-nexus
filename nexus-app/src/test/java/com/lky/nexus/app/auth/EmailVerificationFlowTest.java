package com.lky.nexus.app.auth;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.email.EmailCodeService;
import com.lky.nexus.app.auth.email.EmailPurpose;
import com.lky.nexus.app.auth.email.RecordingEmailSender;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2：已登录用户验证当前绑定邮箱完整链路（MockMvc + 可捕获验证码的 EmailSender）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "nexus.email.code.cooldown=1s",
        "nexus.email.code.ip-per-hour=1000",
        "nexus.email.code.max-attempts=5"
})
class EmailVerificationFlowTest {

    private static final String PASSWORD = "Verify!Email123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingEmailSender recordingEmailSender;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EmailCodeService emailCodeService;

    @BeforeEach
    void clearCapturedCodes() {
        recordingEmailSender.clear();
    }

    @TestConfiguration
    static class CaptureMailConfig {
        @Bean
        @Primary
        RecordingEmailSender recordingEmailSenderBean() {
            return new RecordingEmailSender();
        }
    }

    @Test
    @DisplayName("注册缺邮箱 → 400 + 1001")
    void registerWithoutEmailRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ev-no-mail","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("存量/新绑定邮箱默认未验证；发送→校验成功→emailVerified=true；码不可复用")
    void sendThenVerifySuccessAndCodeIsOneShot() throws Exception {
        register("ev-ok", "ev-ok@example.com");
        String token = loginToken("ev-ok");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.emailVerified").value(false));

        mockMvc.perform(post("/api/users/me/email/verification/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        String code = recordingEmailSender.lastCode();
        assertThat(code).matches("\\d{6}");
        assertThat(recordingEmailSender.events.get(recordingEmailSender.events.size() - 1).purpose())
                .isEqualTo(EmailPurpose.VERIFY_EMAIL);
        // 响应与日志路径不得把码写进 HTTP 体（上面已断言 data 不存在码字段）

        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.email").value("ev-ok@example.com"));

        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));
    }

    @Test
    @DisplayName("错误验证码不能验证成功")
    void wrongCodeRejected() throws Exception {
        register("ev-wrong", "ev-wrong@example.com");
        String token = loginToken("ev-wrong");
        sendCode(token);

        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test
    @DisplayName("未登录无法调用发送/校验接口")
    void anonymousCannotCallVerificationApis() throws Exception {
        mockMvc.perform(post("/api/users/me/email/verification/send"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        mockMvc.perform(post("/api/users/me/email/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("请求体中的 userId 不影响被验证用户（身份只取 JWT）")
    void userIdInBodyIsIgnored() throws Exception {
        register("ev-victim", "ev-victim@example.com");
        register("ev-attacker", "ev-attacker@example.com");
        String attackerToken = loginToken("ev-attacker");
        sendCode(attackerToken);
        String code = recordingEmailSender.lastCode();

        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + attackerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","userId":"999999999999999999"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("ev-attacker"))
                .andExpect(jsonPath("$.data.emailVerified").value(true));

        String victimToken = loginToken("ev-victim");
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + victimToken))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test
    @DisplayName("更换邮箱后必须重新验证；仅改昵称保留验证状态")
    void emailChangeClearsVerificationNicknameKeepsIt() throws Exception {
        register("ev-chg", "ev-chg@example.com");
        String token = loginToken("ev-chg");
        sendCode(token);
        verifyCode(token, recordingEmailSender.lastCode());

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"只改昵称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.email").value("ev-chg@example.com"));

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ev-chg-new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ev-chg-new@example.com"))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test
    @DisplayName("仅大小写变化不算更换邮箱，验证状态保留")
    void caseOnlyEmailChangeKeepsVerification() throws Exception {
        register("ev-case", "ev-case@example.com");
        String token = loginToken("ev-case");
        sendCode(token);
        verifyCode(token, recordingEmailSender.lastCode());

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"EV-Case@Example.COM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ev-case@example.com"))
                .andExpect(jsonPath("$.data.emailVerified").value(true));
    }

    @Test
    @DisplayName("发送后更换邮箱：旧码不能验证新邮箱")
    void codeForOldEmailCannotVerifyNewEmail() throws Exception {
        register("ev-swap", "ev-swap-old@example.com");
        String token = loginToken("ev-swap");
        sendCode(token);
        String oldCode = recordingEmailSender.lastCode();

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ev-swap-new@example.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + oldCode + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test
    @DisplayName("用途隔离：REGISTER 码不能用于 VERIFY_EMAIL HTTP 接口")
    void purposeIsolation() throws Exception {
        register("ev-iso", "ev-iso@example.com");
        String token = loginToken("ev-iso");

        emailCodeService.send("ev-iso@example.com", EmailPurpose.REGISTER, "127.0.0.1");
        String registerCode = recordingEmailSender.lastCode();

        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + registerCode + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test
    @DisplayName("冷却期内重复发送 → RATE_LIMITED；响应不含验证码")
    void cooldownBlocksResend() throws Exception {
        register("ev-cd", "ev-cd@example.com");
        String token = loginToken("ev-cd");
        sendCode(token);

        mockMvc.perform(post("/api/users/me/email/verification/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("其他用户不能改当前用户的 email_verified（条件更新按 JWT 用户）")
    void otherUserCannotFlipVerification() throws Exception {
        register("ev-a", "ev-a@example.com");
        register("ev-b", "ev-b@example.com");
        String tokenA = loginToken("ev-a");
        String tokenB = loginToken("ev-b");
        sendCode(tokenA);
        String codeA = recordingEmailSender.lastCode();

        // B 用 A 的码 → 校验的是 B 自己邮箱上的码，应失败
        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + codeA + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    @Test
    @DisplayName("清空邮箱被拒绝（账号必须绑定邮箱）")
    void clearingEmailRejected() throws Exception {
        register("ev-clr", "ev-clr@example.com");
        String token = loginToken("ev-clr");
        sendCode(token);
        verifyCode(token, recordingEmailSender.lastCode());

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.email").value("ev-clr@example.com"))
                .andExpect(jsonPath("$.data.emailVerified").value(true));
    }

    @Test
    @DisplayName("迁移语义：库中有邮箱但 email_verified=0 时 GET 返回 false")
    void existingEmailDefaultsUnverifiedInDb() throws Exception {
        register("ev-stock", "ev-stock@example.com");
        String token = loginToken("ev-stock");
        SysUserPO user = sysUserMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUserPO>lambdaQuery()
                        .eq(SysUserPO::getUsername, "ev-stock"));
        assertThat(user.getEmail()).isEqualTo("ev-stock@example.com");
        assertThat(user.getEmailVerified()).isEqualTo(0);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    private void sendCode(String token) throws Exception {
        mockMvc.perform(post("/api/users/me/email/verification/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void verifyCode(String token, String code) throws Exception {
        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true));
    }

    private void register(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s"}
                                """.formatted(username, PASSWORD, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String loginToken(String username) throws Exception {
        ResultActions actions = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk());
        String token = JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.data.accessToken");
        assertThat(token).isNotBlank();
        return token;
    }
}
