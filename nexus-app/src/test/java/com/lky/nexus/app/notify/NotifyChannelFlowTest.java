package com.lky.nexus.app.notify;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知通道的接口契约：CRUD + 测试投递 + 越权隔离。
 *
 * <p>断言的重点是「配置对了没」和「失败能被看见」：
 * 一条测试消息投到必然连不上的地址时必须回 {@code lastStatus=FAILED} 并带上原因，
 * 而不是回 200 让用户以为通道是好的。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotifyChannelFlowTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String USERNAME = "notifyuser";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("创建 → 列表 → 测试投递（成功/失败）→ 删除 → 越权不可见")
    void channelLifecycle() throws Exception {
        register(USERNAME);
        String accessToken = JsonPath.read(login(USERNAME), "$.data.accessToken");
        String auth = "Bearer " + accessToken;

        mockMvc.perform(get("/api/users/me/notify-channels").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 1) 邮件通道：测试投递走 console 发信器，必然成功
        String emailBody = mockMvc.perform(post("/api/users/me/notify-channels")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"我的邮箱","type":"EMAIL","target":"me@example.com",
                                 "events":["import.done","import.failed"],"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("EMAIL"))
                .andExpect(jsonPath("$.data.hasSecret").value(false))
                .andExpect(jsonPath("$.data.events.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        // 雪花 id 在 JSON 里是字符串（超出 JS 安全整数），必须显式取 Object 再转
        String emailId = String.valueOf((Object) JsonPath.read(emailBody, "$.data.id"));

        mockMvc.perform(post("/api/users/me/notify-channels/" + emailId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastStatus").value("OK"));

        // 2) Webhook 通道指向必然连不上的端口：测试投递必须报失败并带原因
        String hookBody = mockMvc.perform(post("/api/users/me/notify-channels")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"坏掉的 Hook","type":"WEBHOOK","target":"http://127.0.0.1:1/hook",
                                 "secret":"s3cret","events":["import.done"],"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasSecret").value(true))
                .andExpect(jsonPath("$.data.target").value("http://127.0.0.1:1/hook"))
                .andReturn().getResponse().getContentAsString();
        String hookId = String.valueOf((Object) JsonPath.read(hookBody, "$.data.id"));

        mockMvc.perform(post("/api/users/me/notify-channels/" + hookId + "/test")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.lastError").isNotEmpty());

        // 3) 列表能看到两条
        mockMvc.perform(get("/api/users/me/notify-channels").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // 4) 非法类型 / 非法邮箱明确 400
        mockMvc.perform(post("/api/users/me/notify-channels")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","type":"TELEGRAM","target":"whatever","events":[],"enabled":true}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/users/me/notify-channels")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","type":"EMAIL","target":"not-an-email","events":[],"enabled":true}
                                """))
                .andExpect(status().isBadRequest());

        // 5) 删除后不可见，再删回 3010
        mockMvc.perform(delete("/api/users/me/notify-channels/" + emailId)
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/users/me/notify-channels/" + emailId)
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3010));

        // 6) 别人看不到我的通道
        register("notifyother");
        String otherToken = JsonPath.read(login("notifyother"), "$.data.accessToken");
        mockMvc.perform(get("/api/users/me/notify-channels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(delete("/api/users/me/notify-channels/" + hookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username, username)))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 登录并返回响应体：JsonPath 需要文本，ResultActions 本身不是 JSON。 */
    private String login(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
    }
}
