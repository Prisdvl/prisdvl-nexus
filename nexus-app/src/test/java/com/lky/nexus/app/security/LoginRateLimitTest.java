package com.lky.nexus.app.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录失败限制的 HTTP 层验收（5.6）。阈值压到 3 次。
 *
 * <p>最要紧的一条是：<b>冻结后即使密码正确也登不进去</b>（429 而不是 200）。
 * 若只拦"密码错误"的那条分支，攻击者只要蒙对一次就绕过整个机制——
 * 而这恰恰是暴力破解最可能的收尾方式。
 */
@SpringBootTest(properties = {
        "nexus.security.login.max-failures=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitTest {

    private static final String PASSWORD = "LoginLimit!Pwd1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("★ 连错 3 次后，第 4 次即使密码正确也返回 429 + 7001")
    void lockedOutEvenWithCorrectPassword() throws Exception {
        register("login-lock");

        for (int i = 0; i < 3; i++) {
            login("login-lock", "WrongPassword!9")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(2006));
        }

        // 密码是对的，但仍然进不去 —— 这就是"冻结"
        login("login-lock", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(7001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("暂时锁定")));
    }

    @Test
    @DisplayName("冻结按用户名隔离：锁了 A，B 照常登录")
    void lockIsIsolatedPerUsername() throws Exception {
        register("login-lock-a");
        register("login-lock-b");

        for (int i = 0; i < 3; i++) {
            login("login-lock-a", "WrongPassword!9").andExpect(status().isUnauthorized());
        }
        login("login-lock-a", PASSWORD).andExpect(status().isTooManyRequests());

        login("login-lock-b", PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 成功登录即清零：错 2 次 → 成功 1 次 → 再错 2 次仍然没到阈值 3")
    void successfulLoginClearsFailureCounter() throws Exception {
        register("login-reset");

        login("login-reset", "WrongPassword!9").andExpect(status().isUnauthorized());
        login("login-reset", "WrongPassword!9").andExpect(status().isUnauthorized());
        login("login-reset", PASSWORD).andExpect(status().isOk());

        // 计数已清零，所以这两次只是第 1、2 次失败
        login("login-reset", "WrongPassword!9").andExpect(status().isUnauthorized());
        login("login-reset", "WrongPassword!9").andExpect(status().isUnauthorized());
        // 第 3 次成功——若清零没生效，这里已经是 429 了
        login("login-reset", PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("被冻结的用户名与「不存在」返回不同结果——冻结本身必然暴露账号存在性，这是该机制的固有代价")
    void lockRevealsAccountExistenceByDesign() throws Exception {
        register("login-enum");

        for (int i = 0; i < 3; i++) {
            login("login-enum", "WrongPassword!9").andExpect(jsonPath("$.code").value(2006));
        }

        // 冻结状态下，存在与不存在的用户名都可区分（前者 7001、后者仍走 2006）。
        // 这是"按用户名冻结"无法回避的：不记录失败次数就没法冻结。
        // 明确写在这里，免得以后有人把它当成 bug 去"修"成一致。
        login("login-enum", "WrongPassword!9")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(7001));
        login("never-registered-user", "WrongPassword!9")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2006));
    }

    // ------------------------------------------------------------------

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)));
    }
}
