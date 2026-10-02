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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/users/me/quota}（5.6）：让配额从隐形旋钮变成用户看得见的东西。
 *
 * <p>数值变动**不通过发一次真实的模型请求**来制造：那条路会真的去调模型
 * （测试里是假 Key，等于真的发起一次网络请求）。这里直接调 {@link QuotaService} 的
 * 公开方法把账做出来，然后验证"接口如实反映了这笔账"——
 * 这正是这个接口唯一的职责（记账本身由 {@code QuotaServiceTest} 覆盖）。
 *
 * <p>同样刻意**不去拼 Redis 键**：那会把测试与实现细节（键名、日期片）绑死，
 * 让人将来改键名时被一个跑不通的测试拦住，而它本来什么都没验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaEndpointTest {

    private static final String PASSWORD = "QuotaView!Pwd1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QuotaService quotaService;

    @Test
    @DisplayName("新用户的额度是 0/上限，且 resetAt 是明天的 00:00")
    void freshUserSeesZeroUsage() throws Exception {
        String token = loginFreshUser("quota-view-fresh");

        String body = mockMvc.perform(get("/api/users/me/quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 测试 profile 把次数上限放宽到 1000（见 application-test.yml），token 上限用默认值
                .andExpect(jsonPath("$.data.requestUsed").value(0))
                .andExpect(jsonPath("$.data.requestLimit").value(1000))
                .andExpect(jsonPath("$.data.tokenUsed").value(0))
                .andExpect(jsonPath("$.data.tokenLimit").value(200000))
                .andReturn().getResponse().getContentAsString();

        LocalDateTime resetAt = LocalDateTime.parse(JsonPath.read(body, "$.data.resetAt"));
        assertThat(resetAt).isEqualTo(LocalDateTime.now().toLocalDate().plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("★ 账记进去之后，接口读得出来：次数与 token 都是这个用户自己的")
    void usageIsReflectedForTheOwningUserOnly() throws Exception {
        String token = loginFreshUser("quota-view-used");
        long userId = userIdOf(token);

        quotaService.checkAndConsumeRequest(userId);
        quotaService.checkAndConsumeRequest(userId);
        // token 用量按 prompt + completion 累加：1200 + 800 = 2000
        quotaService.recordTokens(userId, 1200, 800);

        mockMvc.perform(get("/api/users/me/quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestUsed").value(2))
                .andExpect(jsonPath("$.data.tokenUsed").value(2000));

        // 另一个用户看到的必须还是 0：配额是按用户隔离的，
        // 读串了就是"别人把你的额度用光"这种最说不清的故障
        String otherToken = loginFreshUser("quota-view-other");
        mockMvc.perform(get("/api/users/me/quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(jsonPath("$.data.requestUsed").value(0))
                .andExpect(jsonPath("$.data.tokenUsed").value(0));
    }

    @Test
    @DisplayName("不带令牌 → 401（额度是私人信息，不能匿名读）")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me/quota"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------

    private long userIdOf(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // id 被全局序列化为字符串（ADR-017）
        return Long.parseLong(JsonPath.read(body, "$.data.id"));
    }

    private String loginFreshUser(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
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
