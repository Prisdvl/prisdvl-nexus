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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 逐日 Token 消耗接口：形状、窗口边界、按用户过滤。
 *
 * <p>为什么要有「越界参数」用例：{@code days} 是唯一一个能被调用方拧动的旋钮。悄悄截断
 * （比如 1000 天按 365 天返回）会让前端"以为拿到了三年"而画出一年的图，且没有任何报错——
 * 只有明确 1001 才能让错在上游暴露。
 *
 * <p>为什么必须有「别人的消息不进我的日轴」：SQL 的 {@code WHERE user_id = ?} 写错时，
 * 长度与日期断言<b>照样通过</b>，只有具体日期的 Token 数值能抓住。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("逐日 Token 消耗：/api/users/me/stats/daily-tokens")
class DailyTokenStatsTest {

    private static final String PASSWORD = "Stats@12345";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("未登录访问 → 2001（用量数据不接受匿名读）")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me/stats/daily-tokens"))
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("缺省 365 天：连续日轴、升序、两端就是 from/to、最后一天是今天")
    void defaultsToWholeYear() throws Exception {
        String token = registerAndLogin("daily-shape");
        LocalDate today = LocalDate.now();

        mockMvc.perform(get("/api/users/me/stats/daily-tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.days.length()").value(365))
                .andExpect(jsonPath("$.data.from").value(today.minusDays(364).format(DAY)))
                .andExpect(jsonPath("$.data.to").value(today.format(DAY)))
                .andExpect(jsonPath("$.data.days[0].day").value(today.minusDays(364).format(DAY)))
                .andExpect(jsonPath("$.data.days[364].day").value(today.format(DAY)));
    }

    @Test
    @DisplayName("days=3：窗口跟着参数缩，日期仍连续")
    void honoursWindowParameter() throws Exception {
        String token = registerAndLogin("daily-window");
        LocalDate today = LocalDate.now();

        mockMvc.perform(get("/api/users/me/stats/daily-tokens").param("days", "3")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.days.length()").value(3))
                .andExpect(jsonPath("$.data.from").value(today.minusDays(2).format(DAY)))
                .andExpect(jsonPath("$.data.days[0].day").value(today.minusDays(2).format(DAY)));
    }

    @Test
    @DisplayName("days 越界 → 1001（0 天与超过一年都拒绝，不静默截断）")
    void rejectsOutOfRangeWindow() throws Exception {
        String token = registerAndLogin("daily-range");

        mockMvc.perform(get("/api/users/me/stats/daily-tokens").param("days", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(get("/api/users/me/stats/daily-tokens").param("days", "366")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(1001));
    }

    /**
     * 两天前 100、昨天 150+250、今天没有消息：日轴必须按自然日求和、同日多条要相加，
     * 且另一个账号看到的是全 0（证明过滤是按 user_id 而不是全表）。
     */
    @Test
    @DisplayName("按自然日求和：两天前 100，昨天 400，今天 0；别的账号仍是 0")
    void sumsTokensPerNaturalDayPerUser() throws Exception {
        String token = registerAndLogin("daily-sums");
        Long userId = ownerId(token);
        long conversationId = System.nanoTime();
        LocalDateTime twoDaysAgo = LocalDateTime.now().minusDays(2).withHour(10);
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1).withHour(10);

        jdbc.update("INSERT INTO chat_conversation (id, user_id) VALUES (?, ?)", conversationId, userId);
        insertMessage(conversationId * 10 + 1, conversationId, userId, 100, twoDaysAgo);
        insertMessage(conversationId * 10 + 2, conversationId, userId, 150, yesterday);
        insertMessage(conversationId * 10 + 3, conversationId, userId, 250, yesterday);

        mockMvc.perform(get("/api/users/me/stats/daily-tokens").param("days", "3")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.days[0].tokens").value(100))
                .andExpect(jsonPath("$.data.days[1].tokens").value(400))
                .andExpect(jsonPath("$.data.days[2].tokens").value(0));

        String other = registerAndLogin("daily-other");
        mockMvc.perform(get("/api/users/me/stats/daily-tokens").param("days", "3")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(jsonPath("$.data.days[0].tokens").value(0))
                .andExpect(jsonPath("$.data.days[1].tokens").value(0))
                .andExpect(jsonPath("$.data.days[2].tokens").value(0));
    }

    private void insertMessage(long id, long conversationId, Long userId, long tokens, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO chat_message (id, conversation_id, user_id, role, content, total_tokens, created_at)
                VALUES (?,?,?,?,?,?,?)
                """, id, conversationId, userId, "USER", "你好", tokens, at);
    }

    /** 从令牌换用户 ID：不走内部服务，直接读公开接口（雪花 id 以字符串下发）。 */
    private Long ownerId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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
