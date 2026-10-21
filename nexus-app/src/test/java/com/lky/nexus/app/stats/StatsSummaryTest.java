package com.lky.nexus.app.stats;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作台统计接口：形状、鉴权与"按用户过滤"。
 *
 * <p><b>为什么必须有个"新账号全 0"的用例</b>：这条接口的 SQL 全部带
 * {@code WHERE user_id = ?}。如果哪天有人把参数写错（或前端传 userId 进来），
 * 形状断言（trend=14 / heat=30）<b>照样会通过</b>——只有"新账号拿到的计数必须是 0"
 * 才能真正证明过滤生效。这是本类里唯一能抓住越权读的那条断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("工作台统计：/api/stats/summary")
class StatsSummaryTest {

    private static final String PASSWORD = "Stats@12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("未登录访问 → 2001（统计不接受匿名读）")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/stats/summary"))
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("登录后：trend 14 天、heat 30 天、按日期升序、最后一天是今天")
    void returnsCalendarShapedSeries() throws Exception {
        String token = registerAndLogin("stats-shape");

        mockMvc.perform(get("/api/stats/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.trend.length()").value(14))
                .andExpect(jsonPath("$.data.heat.length()").value(30))
                .andExpect(jsonPath("$.data.tokenHeat.length()").value(30))
                .andExpect(jsonPath("$.data.trend[0].day").value(LocalDate.now().minusDays(13).toString()))
                .andExpect(jsonPath("$.data.trend[13].day").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.data.heat[29].day").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.data.tokenHeat[29].day").value(LocalDate.now().toString()));
    }

    /**
     * Token 热力必须真的是「当天 Token 合计」，而不是又一份消息条数。
     *
     * <p>造两条今天写的消息（100 + 150 tokens）：条数应为 2，Token 应为 250。只断言长度
     * 或形状抓不住「两个序列接错字段」这类错，所以这条必须落到具体数值上。
     */
    @Test
    @DisplayName("Token 热力：当天 total_tokens 求和，与消息条数分开统计")
    void sumsTokensPerDay() throws Exception {
        String token = registerAndLogin("stats-tokens");
        Long userId = ownerId(token);
        long conversationId = System.nanoTime();

        jdbc.update("INSERT INTO chat_conversation (id, user_id) VALUES (?, ?)", conversationId, userId);
        jdbc.update("""
                INSERT INTO chat_message (id, conversation_id, user_id, role, content, total_tokens)
                VALUES (?,?,?,?,?,?)
                """, conversationId * 10 + 1, conversationId, userId, "USER", "你好", 100);
        jdbc.update("""
                INSERT INTO chat_message (id, conversation_id, user_id, role, content, total_tokens)
                VALUES (?,?,?,?,?,?)
                """, conversationId * 10 + 2, conversationId, userId, "ASSISTANT", "你好", 150);

        mockMvc.perform(get("/api/stats/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.heat[29].count").value(2))
                .andExpect(jsonPath("$.data.tokenHeat[29].count").value(250));
    }

    @Test
    @DisplayName("全新账号：所有计数为 0、活动流为空 —— 证明 SQL 确实按 user_id 过滤")
    void newUserSeesNothing() throws Exception {
        String token = registerAndLogin("stats-zero");

        mockMvc.perform(get("/api/stats/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trend[0].count").value(0))
                .andExpect(jsonPath("$.data.trend[13].count").value(0))
                .andExpect(jsonPath("$.data.heat[29].count").value(0))
                .andExpect(jsonPath("$.data.tokenHeat[29].count").value(0))
                .andExpect(jsonPath("$.data.activity.length()").value(0));
    }

    /** 从令牌换用户 ID：不去猜用户表的表名/字段，直接走公开接口。 */
    private Long ownerId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 响应里的 id 是字符串（雪花 id 超过 JS 安全整数，接口统一按字符串下发）
        return Long.parseLong(String.valueOf((Object) JsonPath.read(body, "$.data.id")));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s","email":"%s@example.com"}
                        """.formatted(username, PASSWORD, username)));

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