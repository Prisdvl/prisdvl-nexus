package com.lky.nexus.app.plugin;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 插件开关与配置（{@code user_plugin}）。
 *
 * <p>这里钉住三条语义，它们都是"用户会立刻感觉到"的地方：
 * <ul>
 *   <li><b>没有行 = 启用</b>：新用户 {@code GET} 不会 404，而是拿到默认值——
 *       插件目录活在前端，服务端只记覆盖；</li>
 *   <li><b>config 传 null 与传 {} 不同</b>：前者"别动"，后者"清空"。混为一谈，
 *       用户就永远清不掉配置；</li>
 *   <li><b>两个用户互不可见</b>：A 关掉插件不影响 B。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PluginToggleTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("没动过的插件返回默认值（启用、空配置），列表为空而不是 404")
    void untoucedPluginFallsBackToDefault() throws Exception {
        String token = registerAndLogin("plugin-default");

        mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("web-fetch"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.config").isEmpty());
    }

    @Test
    @DisplayName("启停与配置 roundtrip：只改 enabled 时配置保留，传空对象才清空")
    void toggleAndConfigureRoundTrip() throws Exception {
        String token = registerAndLogin("plugin-roundtrip");

        mockMvc.perform(put("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"config":{"endpoint":"https://example.com","retries":3}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.config.endpoint").value("https://example.com"))
                .andExpect(jsonPath("$.data.config.retries").value(3));

        // 只改 enabled：配置必须还在（null = 别动）
        mockMvc.perform(put("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.config.endpoint").value("https://example.com"));

        mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("web-fetch"))
                .andExpect(jsonPath("$.data[0].enabled").value(true));

        // 空对象 = 清空（这是用户唯一的"恢复默认"手段）
        mockMvc.perform(put("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"config":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.config").isEmpty())
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    @DisplayName("两个用户的开关互不可见")
    void pluginsArePerUser() throws Exception {
        String alice = registerAndLogin("plugin-alice");
        String bob = registerAndLogin("plugin-bob");

        mockMvc.perform(put("/api/plugins/code-review")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(get("/api/plugins/code-review")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("非法 code 一律 400（1001）：它同时是前端传参与 URL 注入的边界")
    void invalidCodeIsRejected() throws Exception {
        String token = registerAndLogin("plugin-bad-code");

        MvcResult result = mockMvc.perform(put("/api/plugins/Bad Code!")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        Object code = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.code");
        assertThat(code).isEqualTo(1001);
    }

    @Test
    @DisplayName("未认证访问插件接口 → 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/plugins"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mcp-servers"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private String registerAndLogin(String prefix) throws Exception {
        String username = prefix + "-" + SEQ.incrementAndGet() + "-" + (System.nanoTime() % 100_000);
        String email = username + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s"}
                                """.formatted(username, PASSWORD, email)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.accessToken");
    }
}
