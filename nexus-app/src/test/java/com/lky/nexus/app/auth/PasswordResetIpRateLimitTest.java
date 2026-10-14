package com.lky.nexus.app.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密码重置请求的 IP 维限流（独立上下文，ip-per-hour=2）。
 *
 * <p>准备数据时直接写 {@code email_verified=1}，避免验邮发送占用同一 IP 配额。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "nexus.email.code.cooldown=1s",
        "nexus.email.code.ip-per-hour=2"
})
class PasswordResetIpRateLimitTest {

    private static final String PASSWORD = "IpLimit!Pass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingEmailSender recordingEmailSender;

    @Autowired
    private SysUserMapper sysUserMapper;

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
    @DisplayName("同 IP 超过 ip-per-hour 后返回 429")
    void ipRateLimitOnResetRequest() throws Exception {
        String e1 = "pr-ip1-" + System.nanoTime() + "@example.com";
        String e2 = "pr-ip2-" + System.nanoTime() + "@example.com";
        String e3 = "pr-ip3-" + System.nanoTime() + "@example.com";
        registerVerified("pr-ip1-" + System.nanoTime() % 100000, e1);
        registerVerified("pr-ip2-" + System.nanoTime() % 100000, e2);
        registerVerified("pr-ip3-" + System.nanoTime() % 100000, e3);

        mockMvc.perform(post("/api/auth/password/reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + e1 + "\"}")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.50");
                            return req;
                        }))
                .andExpect(status().isOk());
        Thread.sleep(1100);

        mockMvc.perform(post("/api/auth/password/reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + e2 + "\"}")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.50");
                            return req;
                        }))
                .andExpect(status().isOk());
        Thread.sleep(1100);

        mockMvc.perform(post("/api/auth/password/reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + e3 + "\"}")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.50");
                            return req;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED.getCode()));
    }

    @Test
    @DisplayName("未注册 / 已验证邮箱共用同一 IP 配额：混用后同样 429")
    void ipRateLimitUniformAcrossUnknownAndVerified() throws Exception {
        String unknown1 = "pr-unk1-" + System.nanoTime() + "@example.com";
        String verified = "pr-mix-" + System.nanoTime() + "@example.com";
        String unknown2 = "pr-unk2-" + System.nanoTime() + "@example.com";
        registerVerified("pr-mix-" + System.nanoTime() % 100000, verified);

        // 1) 未注册邮箱也计入配额
        mockMvc.perform(post("/api/auth/password/reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + unknown1 + "\"}")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.51");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(recordingEmailSender.events).isEmpty();

        // 2) 已验证邮箱仍计入同一配额
        mockMvc.perform(post("/api/auth/password/reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + verified + "\"}")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.51");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(recordingEmailSender.events).hasSize(1);

        // 3) 再请求（任意合法邮箱）→ 统一 429
        mockMvc.perform(post("/api/auth/password/reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + unknown2 + "\"}")
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.51");
                            return req;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(ErrorCode.RATE_LIMITED.getCode()));
    }

    private void registerVerified(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s"}
                                """.formatted(username, PASSWORD, email)))
                .andExpect(status().isOk());

        sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                .eq(SysUserPO::getUsername, username)
                .set(SysUserPO::getEmailVerified, 1));
    }
}
