package com.lky.nexus.app.auth;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.totp.TotpAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两步验证的端到端契约：绑定 → 登录需要第二因子 → 恢复码兜底 → 关闭。
 *
 * <p>这里断言的是<b>状态机</b>而不是页面：绑定完成后旧密码必须单独登不进来（2011），
 * 恢复码只能用一次（第二次 2012），关闭后登录回到单因子。
 * 一个只测"setup 返回了 secret"的用例，会漏掉所有真正会锁死用户的分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TwoFactorFlowTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String USERNAME = "totpuser";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("绑定 → 登录要求验证码 → 错误码被拒 → 正确码通过 → 恢复码一次性 → 关闭后回到单因子")
    void totpLifecycle() throws Exception {
        register(USERNAME);
        String token = login(USERNAME, PASSWORD, null).andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(token, "$.data.accessToken");

        // 初始：未开启
        mockMvc.perform(get("/api/users/me/totp").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.recoveryCodesLeft").value(0));

        // setup 只发候选密钥，账号仍是单因子
        String setupBody = mockMvc.perform(post("/api/users/me/totp/setup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").isString())
                .andExpect(jsonPath("$.data.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
                .andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(setupBody, "$.data.secret");

        // 尚未校验时登录不要求验证码：否则用户放弃绑定时会被锁在门外
        login(USERNAME, PASSWORD, null).andExpect(status().isOk());

        String currentCode = TotpAlgorithm.code(secret, TotpAlgorithm.timeStep(Instant.now(), 30), 6);

        // 错误的 6 位码不能启用
        // 注意口径：enable 是**已认证**接口，填错码回 1001/400 而不是 2012/401——
        // 401 会被前端全局拦截器当成会话失效，把"填错一格"变成登出（见 docs/api.md）。
        String wrongCode = currentCode.equals("000000") ? "000001" : "000000";
        mockMvc.perform(post("/api/users/me/totp/enable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + wrongCode + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // 正确码启用，拿到 10 枚恢复码
        String enableBody = mockMvc.perform(post("/api/users/me/totp/enable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + currentCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCodes.length()").value(10))
                .andReturn().getResponse().getContentAsString();
        String recoveryCode = JsonPath.read(enableBody, "$.data.recoveryCodes[0]");
        assertThat(recoveryCode).matches("[A-Z2-9]{5}-[A-Z2-9]{5}");

        // 状态跟着变
        mockMvc.perform(get("/api/users/me/totp").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.recoveryCodesLeft").value(10));

        // 已开启后不能再次绑定（换密钥必须先关闭）
        mockMvc.perform(post("/api/users/me/totp/setup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(2014));

        // 只给密码 → 2011；密码 + 错误码 → 2012
        login(USERNAME, PASSWORD, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2011));
        login(USERNAME, PASSWORD, wrongCode)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2012));

        // 密码 + 当前验证码 → 放行
        login(USERNAME, PASSWORD, currentCode).andExpect(status().isOk());

        // 恢复码能登录，且只能用一次
        login(USERNAME, PASSWORD, recoveryCode).andExpect(status().isOk());
        login(USERNAME, PASSWORD, recoveryCode)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2012));

        // 关闭：密码错 → 1001；密码对 → 成功，恢复单因子登录
        mockMvc.perform(post("/api/users/me/totp/disable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(post("/api/users/me/totp/disable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        login(USERNAME, PASSWORD, null).andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me/totp").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.recoveryCodesLeft").value(0));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username, username)))
                .andExpect(jsonPath("$.code").value(0));
    }

    private ResultActions login(String username, String password, String totpCode) throws Exception {
        // totpCode 为 null 时**不写这个字段**，模拟老客户端：服务端必须按缺字段处理成 2011
        String body = totpCode == null
                ? """
                  {"username":"%s","password":"%s"}
                  """.formatted(username, password)
                : """
                  {"username":"%s","password":"%s","totpCode":"%s"}
                  """.formatted(username, password, totpCode);
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
