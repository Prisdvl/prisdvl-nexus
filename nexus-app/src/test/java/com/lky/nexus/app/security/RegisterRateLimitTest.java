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
 * 注册入口按来源 IP 限流（5.6）。上限压到 2 次/小时。
 *
 * <p>为什么注册也值得限流：批量注册是账号体系最常见的滥用方式
 * （刷号、薅新用户额度、把用户表撑大）。而"登录失败限制"管不到它——
 * 那套是用户名维度的，每次注册都是全新用户名。
 *
 * <p>⚠️ 未认证请求没有 {@code SecurityContext}，所以这里只能按 {@code getRemoteAddr()} 计。
 * 反向代理之后那会变成代理地址（全体用户共用一个桶），届时必须先配好可信代理链；
 * 本期刻意**不信任** {@code X-Forwarded-For}（伪造它比伪造 IP 容易得多）。
 */
@SpringBootTest(properties = {
        "nexus.security.register.per-hour-per-ip=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegisterRateLimitTest {

    private static final String PASSWORD = "Register!Pwd1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("同一 IP 的第 3 次注册 → 429 + 7001，并给出 Retry-After")
    void registrationIsLimitedPerIp() throws Exception {
        register("reg-limit-1", "10.1.1.1").andExpect(status().isOk());
        register("reg-limit-2", "10.1.1.1").andExpect(status().isOk());

        register("reg-limit-3", "10.1.1.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(7001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("注册过于频繁")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Retry-After", "3600"));
    }

    @Test
    @DisplayName("计数器按 IP 隔离：A 打满额度不影响 B 注册")
    void registrationLimitIsPerIp() throws Exception {
        register("reg-ip-a1", "10.3.3.3").andExpect(status().isOk());
        register("reg-ip-a2", "10.3.3.3").andExpect(status().isOk());
        register("reg-ip-a3", "10.3.3.3").andExpect(status().isTooManyRequests());

        register("reg-ip-b1", "10.4.4.4").andExpect(status().isOk());
    }

    @Test
    @DisplayName("被注册限流不影响已存在用户的登录（否则等于把所有人一起锁在门外）")
    void loginStillWorksWhenRegistrationIsThrottled() throws Exception {
        String ip = "10.2.2.2";
        register("reg-limit-login", ip).andExpect(status().isOk());
        register("reg-limit-fill", ip).andExpect(status().isOk());
        // 到这里该 IP 的额度已用尽
        register("reg-limit-blocked", ip).andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"reg-limit-login","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 每个用例用独立的来源 IP：计数器活在共享的应用上下文里（同一个 @SpringBootTest 配置
     * ⇒ 同一个上下文 ⇒ 同一个 InMemorySecurityStore），@SpringBootTest 又不保证方法执行顺序。
     * 共用 127.0.0.1 的话，用例会按执行顺序互相吃掉额度——那是典型的"单独跑全过、
     * 一起跑随机挂"，排查起来极其费时。
     */
    private org.springframework.test.web.servlet.ResultActions register(String username, String remoteAddr)
            throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, PASSWORD)));
    }
}
