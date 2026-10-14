package com.lky.nexus.app.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储全挂时的整体行为（5.6）——这是本期设计里最值钱的一条测试。
 *
 * <p>同一个故障（Redis 不可用）下，三个组件要有**三种不同**的反应，
 * 而它们全都发生在同一次请求链路上：
 * <ol>
 *   <li><b>令牌版本校验：降级</b>——回落到查库，因此拿合法令牌照样能访问。
 *       既不放行被吊销的令牌，也不把所有人挡在门外；</li>
 *   <li><b>限流：fail-open</b>——放行。限流失效只是"有人多刷了几次"；</li>
 *   <li><b>每日配额：fail-closed</b>——拒绝，报 7002。配额失效是"真金白银被烧光"。</li>
 * </ol>
 *
 * <p>把这三条放进同一个用例里，是因为它们的差异只有在"同一次故障"下才能被看见：
 * 分成三个独立的单测，很容易在后续重构里被"统一处置"掉，而那种统一恰恰会让
 * 配额失去意义（全 fail-open）或者让系统整体不可用（全 fail-closed）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityStoreDegradationTest {

    private static final String PASSWORD = "Degraded!Pwd12";

    @Autowired
    private MockMvc mockMvc;

    /** 被替换成"一碰就炸"的存储：模拟 Redis 整个不可用。 */
    @MockitoBean
    private SecurityStore securityStore;

    @BeforeEach
    void breakTheStore() {
        given(securityStore.get(anyString())).willThrow(new IllegalStateException("Redis 不可用"));
        given(securityStore.add(anyString(), anyLong(), any(Duration.class)))
                .willThrow(new IllegalStateException("Redis 不可用"));
        given(securityStore.increment(anyString(), any(Duration.class)))
                .willThrow(new IllegalStateException("Redis 不可用"));
        given(securityStore.exists(anyString())).willThrow(new IllegalStateException("Redis 不可用"));
        willThrow(new IllegalStateException("Redis 不可用")).given(securityStore).delete(any(String[].class));
        willThrow(new IllegalStateException("Redis 不可用")).given(securityStore).put(anyString(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("★ 同一次故障下：登录照常（fail-open + 降级），但模型入口被拒（fail-closed）")
    void storeFailureDegradesPerConcern() throws Exception {
        // 1) 登录：限流 fail-open + 令牌版本缓存降级 —— 必须成功
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"degraded-user","password":"%s","email":"degraded-user@example.com"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk());

        String token = JsonPath.read(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"degraded-user","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.accessToken");

        // 2) 令牌版本校验：缓存读失败 → 落回查库 → 令牌照常有效（不被误吊销）
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("degraded-user"));

        // 3) 模型入口：配额 fail-closed → 拒绝，并如实说"服务不可用"而不是"额度用尽"
        mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("暂时不可用")));
    }

    @Test
    @DisplayName("存储全挂也不影响不花钱的接口：改密码路径只受限流（fail-open），不会被配额牵连")
    void nonModelEndpointsStillUsable() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"degraded-plain","password":"%s","email":"degraded-plain@example.com"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk());

        String token = JsonPath.read(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"degraded-plain","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.accessToken");

        // 改密码（extraPaths：只限流，不计配额）→ 限流 fail-open ⇒ 一路放行到控制器
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"ChangedPwd!42"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
