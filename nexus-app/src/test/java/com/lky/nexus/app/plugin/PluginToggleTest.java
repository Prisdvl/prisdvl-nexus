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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 插件目录与用户安装状态（{@code plugin_catalog} + {@code user_plugin}）。
 *
 * <p>这里钉住四条语义，它们都是"用户会立刻感觉到"的地方：
 * <ul>
 *   <li><b>没有行 = 未安装</b>：目录在服务端（V34），新用户拿到的必须是
 *       {@code enabled=false} —— 换设备、换账号都不该看到别人的安装状态；</li>
 *   <li><b>目录是权威</b>：不在目录里的 code → 404（9301），不能往库里塞市场里没有的东西；</li>
 *   <li><b>config 传 null 与传 {} 不同</b>：前者"别动"，后者"清空"。混为一谈，
 *       用户就永远清不掉配置；</li>
 *   <li><b>两个用户互不可见</b>：A 装了什么不影响 B。</li>
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
    @DisplayName("没装过的插件返回默认未安装（enabled=false），列表是目录全集而不是空数组")
    void untoucedPluginFallsBackToDefault() throws Exception {
        String token = registerAndLogin("plugin-default");

        MvcResult list = mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        // 目录来自服务端 seed：至少要有几条，且一条都没被这个新账号安装
        List<Object> codes = body(list).read("$.data[*].code");
        assertThat(codes).isNotEmpty();
        List<Object> enabled = body(list).read("$.data[*].enabled");
        assertThat(enabled).containsOnly(false);
        // 目录字段一起返回，前端不再自带清单
        assertThat((String) body(list).read("$.data[0].name")).isNotBlank();
        assertThat((Object) body(list).read("$.data[0].category")).isNotNull();

        mockMvc.perform(get("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("web-fetch"))
                .andExpect(jsonPath("$.data.name").value("Web Fetch"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.config").isEmpty());
    }

    @Test
    @DisplayName("换第二个账号登录，目录里的插件一律默认未安装")
    void secondAccountSeesNothingInstalled() throws Exception {
        String alice = registerAndLogin("plugin-alice-installs");
        String bob = registerAndLogin("plugin-bob-fresh");

        mockMvc.perform(put("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mockMvc.perform(put("/api/plugins/code-review")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"config\":{\"level\":\"strict\"}}"))
                .andExpect(status().isOk());

        // Alice 装了两个
        MvcResult aliceList = mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(aliceList).<List<String>>read("$.data[?(@.enabled == true)].code"))
                .containsExactlyInAnyOrder("web-fetch", "code-review");

        // Bob 是全新账号：一行安装状态都不该借过来，配置也不该泄露
        MvcResult bobList = mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(bobList).<List<Boolean>>read("$.data[*].enabled")).containsOnly(false);
        assertThat(body(bobList).<List<Object>>read("$.data[*].config"))
                .allSatisfy(config -> assertThat((java.util.Map<?, ?>) config).isEmpty());
        // 单个查询同样默认未安装，不是 404
        mockMvc.perform(get("/api/plugins/code-review")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
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

        MvcResult list = mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        // 列表里只有这一条是已安装，其余仍是"未装"
        assertThat(body(list).<List<String>>read("$.data[?(@.enabled == true)].code"))
                .containsExactly("web-fetch");

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
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(get("/api/plugins/code-review")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        MvcResult bobList = mockMvc.perform(get("/api/plugins")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(bobList).<List<String>>read("$.data[?(@.enabled == true)].code")).isEmpty();
    }

    @Test
    @DisplayName("插件配置超过 16 KB → 400（1001），且什么都没写进去")
    void oversizedConfigIsRejected() throws Exception {
        String token = registerAndLogin("plugin-big-config");
        String big = "x".repeat(20_000);

        MvcResult result = mockMvc.perform(put("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"config\":{\"blob\":\"%s\"}}".formatted(big)))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat((Integer) body(result).read("$.code")).isEqualTo(1001);

        // 拒绝发生在写库之前：这条插件仍然是"没装"
        mockMvc.perform(get("/api/plugins/web-fetch")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.config").isEmpty());
    }

    @Test
    @DisplayName("目录里没有的 code → 404（9301），装不上市场里不存在的插件")
    void unknownPluginIsNotFound() throws Exception {
        String token = registerAndLogin("plugin-unknown");

        MvcResult result = mockMvc.perform(put("/api/plugins/not-in-catalog")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat((Integer) body(result).read("$.code")).isEqualTo(9301);

        mockMvc.perform(get("/api/plugins/not-in-catalog")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
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
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private com.jayway.jsonpath.DocumentContext body(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

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
