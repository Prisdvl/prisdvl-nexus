package com.lky.nexus.app.auth;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.email.EmailPurpose;
import com.lky.nexus.app.auth.email.RecordingEmailSender;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.service.PasswordResetService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.lky.nexus.app.auth.email.EmailCodeStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3：忘记密码 / 邮箱验证码重置完整链路（MockMvc + RecordingEmailSender）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "nexus.email.code.cooldown=1s",
        "nexus.email.code.ip-per-hour=1000",
        "nexus.email.code.max-attempts=5",
        "nexus.email.code.expire=5m"
})
class PasswordResetFlowTest {

    private static final String OLD_PASSWORD = "OldReset!Pass123";
    private static final String NEW_PASSWORD = "NewReset!Pass456";
    private static final String ACK = PasswordResetService.REQUEST_ACK_MESSAGE;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingEmailSender recordingEmailSender;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EmailCodeStore emailCodeStore;

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

    // ------------------------------------------------------------------
    // A. 重置验证码请求
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已验证邮箱可触发发送；响应不含验证码明文")
    void verifiedEmailTriggersSend() throws Exception {
        String email = uniqueEmail("ok");
        registerAndVerify("pr-ok", email);

        int before = recordingEmailSender.events.size();
        String response = requestReset(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value(ACK))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(recordingEmailSender.events).hasSize(before + 1);
        RecordingEmailSender.Event ev = recordingEmailSender.events.get(recordingEmailSender.events.size() - 1);
        assertThat(ev.purpose()).isEqualTo(EmailPurpose.RESET_PASSWORD);
        assertThat(ev.email()).isEqualTo(email);
        assertThat(ev.code()).matches("\\d{6}");
        assertThat(response).doesNotContain(ev.code());
    }

    @Test
    @DisplayName("未注册邮箱不发送；响应与已验证一致（防枚举）")
    void unknownEmailNoSendSameResponse() throws Exception {
        int before = recordingEmailSender.events.size();
        String body = requestReset("unknown-pr@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value(ACK))
                .andReturn().getResponse().getContentAsString();

        assertThat(recordingEmailSender.events).hasSize(before);
        assertThat(body).doesNotContain("不存在").doesNotContain("未验证");
    }

    @Test
    @DisplayName("未验证邮箱不发送；响应与已验证一致（防枚举）")
    void unverifiedEmailNoSendSameResponse() throws Exception {
        String email = uniqueEmail("unv");
        register("pr-unv", email);
        int before = recordingEmailSender.events.size();

        requestReset(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value(ACK));

        assertThat(recordingEmailSender.events).hasSize(before);
    }

    @Test
    @DisplayName("同邮箱冷却：不重复发信，响应仍为模糊成功（防枚举）")
    void emailCooldownAppliesWithoutLeaking() throws Exception {
        String email = uniqueEmail("cd");
        registerAndVerify("pr-cd", email);
        requestReset(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value(ACK));
        int afterFirst = recordingEmailSender.events.size();

        requestReset(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value(ACK));
        assertThat(recordingEmailSender.events).hasSize(afterFirst);
    }

    @Test
    @DisplayName("非法邮箱格式 → 400 参数错误")
    void invalidEmailRejected() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));
    }

    // ------------------------------------------------------------------
    // B. 密码重置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 正确验证码重置密码；新密码可登录、旧密码不可；旧 JWT 失效")
    void resetSuccessRevokesOldTokenAndSwitchesPassword() throws Exception {
        String email = uniqueEmail("full");
        registerAndVerify("pr-full", email);
        String oldLogin = login("pr-full", OLD_PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String oldToken = JsonPath.read(oldLogin, "$.data.accessToken");
        String oldRefresh = JsonPath.read(oldLogin, "$.data.refreshToken");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isOk());

        String code = sendResetCode(email);
        confirmReset(email, code, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value(PasswordResetService.CONFIRM_OK_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        // refresh 会话一并撤销
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        login("pr-full", OLD_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2006));
        String newToken = loginToken("pr-full", NEW_PASSWORD);
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("错误验证码不能重置")
    void wrongCodeRejected() throws Exception {
        String email = uniqueEmail("wrong");
        registerAndVerify("pr-wrong", email);
        sendResetCode(email);

        confirmReset(email, "000000", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));

        login("pr-wrong", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("已消费验证码不能再次重置")
    void consumedCodeCannotReuse() throws Exception {
        String email = uniqueEmail("once");
        registerAndVerify("pr-once", email);
        String code = sendResetCode(email);
        confirmReset(email, code, NEW_PASSWORD).andExpect(status().isOk());

        confirmReset(email, code, "Another!Pass789")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));
    }

    @Test
    @DisplayName("错误次数耗尽后不能重置")
    void exhaustedAttemptsRejected() throws Exception {
        String email = uniqueEmail("exh");
        registerAndVerify("pr-exh", email);
        sendResetCode(email);

        for (int i = 0; i < 4; i++) {
            confirmReset(email, "00000" + i, NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));
        }
        // 第 5 次错误 → 耗尽
        confirmReset(email, "000004", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_TOO_MANY_ATTEMPTS.getCode()));

        String realCode = recordingEmailSender.lastCode();
        confirmReset(email, realCode, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));
        login("pr-exh", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("未验证邮箱不能用验证码重置（即使有人伪造码路径也不发送）")
    void unverifiedCannotConfirmWithoutCode() throws Exception {
        String email = uniqueEmail("uvc");
        register("pr-uvc", email);

        confirmReset(email, "123456", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));
        login("pr-uvc", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("邮箱更换后旧验证码不能重置密码")
    void emailChangeInvalidatesPendingReset() throws Exception {
        String email = uniqueEmail("chg");
        registerAndVerify("pr-chg", email);
        String token = loginToken("pr-chg", OLD_PASSWORD);
        String code = sendResetCode(email);

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"changed-" + email + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(false));

        confirmReset(email, code, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));

        login("pr-chg", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("清除验证状态后旧验证码不能重置")
    void clearVerifiedBlocksReset() throws Exception {
        String email = uniqueEmail("clr");
        registerAndVerify("pr-clr", email);
        String code = sendResetCode(email);

        SysUserPO user = sysUserMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUserPO>lambdaQuery()
                        .eq(SysUserPO::getUsername, "pr-clr"));
        user.setEmailVerified(0);
        sysUserMapper.updateById(user);

        confirmReset(email, code, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));
        login("pr-clr", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("新密码过短拒绝重置")
    void shortPasswordRejected() throws Exception {
        String email = uniqueEmail("short");
        registerAndVerify("pr-short", email);
        String code = sendResetCode(email);

        confirmReset(email, code, "1234567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));
        login("pr-short", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("并发消费同一验证码：最多一个成功")
    void concurrentConfirmAtMostOneSuccess() throws Exception {
        String email = uniqueEmail("race");
        registerAndVerify("pr-race", email);
        String code = sendResetCode(email);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> winnerPwd = new java.util.concurrent.atomic.AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String pwd = "Race!Pass" + i + "xx";
            futures.add(pool.submit(() -> {
                start.await();
                ResultActions ra = confirmReset(email, code, pwd);
                int status = ra.andReturn().getResponse().getStatus();
                if (status == 200) {
                    successes.incrementAndGet();
                    winnerPwd.compareAndSet(null, pwd);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(winnerPwd.get()).isNotBlank();
        login("pr-race", winnerPwd.get()).andExpect(status().isOk());
        login("pr-race", OLD_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("请求体中的 userId 不会决定重置目标")
    void userIdInBodyIsIgnored() throws Exception {
        String emailVictim = uniqueEmail("vic");
        String emailAttacker = uniqueEmail("atk");
        registerAndVerify("pr-vic", emailVictim);
        registerAndVerify("pr-atk", emailAttacker);
        String code = sendResetCode(emailAttacker);

        mockMvc.perform(post("/api/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"email":"%s","code":"%s","newPassword":"%s"}
                                """.formatted(emailAttacker, code, NEW_PASSWORD)))
                .andExpect(status().isOk());

        login("pr-vic", OLD_PASSWORD).andExpect(status().isOk());
        login("pr-atk", OLD_PASSWORD).andExpect(status().isUnauthorized());
        login("pr-atk", NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("VERIFY_EMAIL 验证码不能用于重置密码")
    void verifyEmailPurposeCannotResetPassword() throws Exception {
        String email = uniqueEmail("iso");
        register("pr-iso", email);
        String token = loginToken("pr-iso", OLD_PASSWORD);

        mockMvc.perform(post("/api/users/me/email/verification/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        String verifyCode = recordingEmailSender.lastCode();

        confirmReset(email, verifyCode, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));
        login("pr-iso", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("过期验证码不能重置")
    void expiredCodeRejected() throws Exception {
        String email = uniqueEmail("exp");
        registerAndVerify("pr-exp", email);
        requestReset(email).andExpect(status().isOk());
        String code = recordingEmailSender.lastCode();

        // 直接写入极短 TTL 覆盖刚保存的码（同 purpose/email）
        emailCodeStore.save(EmailPurpose.RESET_PASSWORD, email, code, java.time.Duration.ofMillis(40));
        Thread.sleep(80);

        confirmReset(email, code, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_CODE_INVALID.getCode()));
        login("pr-exp", OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("公开端点：未登录可调用 request/confirm")
    void publicEndpointsAllowAnonymous() throws Exception {
        requestReset("anon-pr@example.com").andExpect(status().isOk());
        confirmReset("anon-pr@example.com", "123456", NEW_PASSWORD)
                .andExpect(status().isBadRequest()); // 无码 → 400，但不是 401
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/password/reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions confirmReset(String email, String code, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/password/reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","code":"%s","newPassword":"%s"}
                        """.formatted(email, code, newPassword)));
    }

    private String sendResetCode(String email) throws Exception {
        requestReset(email).andExpect(status().isOk());
        String code = recordingEmailSender.lastCode();
        assertThat(code).matches("\\d{6}");
        // 放冷却，便于同测内再次发送（cooldown=1s）
        Thread.sleep(1100);
        return code;
    }

    private void registerAndVerify(String username, String email) throws Exception {
        register(username, email);
        String token = loginToken(username, OLD_PASSWORD);
        mockMvc.perform(post("/api/users/me/email/verification/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        String code = recordingEmailSender.lastCode();
        mockMvc.perform(post("/api/users/me/email/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true));
        Thread.sleep(1100); // 冷却：验邮与重置共用 cooldown key
    }

    private void register(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s"}
                                """.formatted(username, OLD_PASSWORD, email)))
                .andExpect(status().isOk());
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)));
    }

    private String loginToken(String username, String password) throws Exception {
        ResultActions actions = login(username, password).andExpect(status().isOk());
        String token = JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.data.accessToken");
        assertThat(token).isNotBlank();
        return token;
    }

    private static String uniqueEmail(String tag) {
        return "pr-" + tag + "-" + System.nanoTime() + "@example.com";
    }
}
