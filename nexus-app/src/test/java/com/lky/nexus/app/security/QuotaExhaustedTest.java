package com.lky.nexus.app.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 每日配额用尽时的 HTTP 行为（5.6）：{@code QUOTA_EXHAUSTED(7002)} / 403。
 *
 * <p>限额置 0 ⇒ 第一次请求就超。限流放宽到 100，确保被拦下的是**配额**而不是限流
 * （否则这个用例会在 7001 上"通过"，实际什么都没验到）。
 *
 * <p>403 而不是 429：429 的语义是"慢点再来"，而配额用尽是"今天不行了，明天来"
 * ——前端要据此显示完全不同的文案（前者提示稍后重试，后者提示明天再来）。
 * 因此这里也断言<b>不返回 Retry-After</b>：回一个 60 秒会诱导客户端白试一整天。
 */
@SpringBootTest(properties = {
        "nexus.security.rate-limit.per-minute=100",
        "nexus.security.quota.daily-requests=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaExhaustedTest {

    private static final String PASSWORD = "Quota!Pwd1234";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("★ 配额用尽 → 403 + 7002，且不带 Retry-After（403 的语义是「明天再来」）")
    void exhaustedQuotaReturns403WithoutRetryAfter() throws Exception {
        String token = loginFreshUser("quota-exhausted");

        mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("次数已用尽")))
                // 依旧是开流前的 JSON，不是 SSE
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("配额用尽不影响注册与登录（否则用户连「重新登录看看」都做不到）")
    void authEndpointsKeepWorkingWhenQuotaExhausted() throws Exception {
        loginFreshUser("quota-auth-still-works");
    }

    private String loginFreshUser(String username) throws Exception {
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

        return JsonPath.read(body, "$.data.accessToken");
    }
}
