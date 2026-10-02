package com.lky.nexus.app.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流的 HTTP 层验收（5.6）。限额置 0 ⇒ 第一次请求就被拦。
 *
 * <p><b>为什么用 0 而不是"打满再越界"</b>：这样可以直接拿**真实配置的路径**
 * （{@code /api/chat/stream}、知识库问答那几个）来验，
 * 而且第一个请求就返回——不会真的去调模型
 * （测试环境没有可用 key，真调一次要等超时）。用假路径测虽然干净，
 * 但验的就不是"线上那份路径清单"了。
 *
 * <p>最要紧的一条断言是 <b>Content-Type 必须是 application/json，不能是 text/event-stream</b>：
 * 它证明这次拒绝发生在 {@code new SseEmitter} **之前**。若过滤器挂错了位置，
 * 客户端会先收到 200 + event-stream，再在事件流里读到错误——那时的错误处理、
 * 前端解析、以及"到底算不算成功"全都不一样（Phase 5.2 的口径：开流前失败走 JSON）。
 */
@SpringBootTest(properties = {
        "nexus.security.rate-limit.per-minute=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitQuotaTest {

    private static final String PASSWORD = "RateLimit!Pwd1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("★ 模型入口超限 → 429 + 7001，且 Content-Type 是 JSON 而不是 text/event-stream（证明在开流前拒绝）")
    void streamEndpointRejectedBeforeStreamOpens() throws Exception {
        String token = loginFreshUser("ratelimit-stream");

        String responseBody = mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(7001))
                // 这条就是"开流前拒绝"的证据
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("text/event-stream"))))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn().getResponse().getContentAsString();

        // 统一返回体的形状不能因为"错误来自过滤器"就走样
        org.assertj.core.api.Assertions.assertThat(responseBody).contains("\"code\":7001").contains("\"data\":null");
    }

    @Test
    @DisplayName("知识库问答入口同样被拦（配额路径清单覆盖到的不止 chat）")
    void ragAskEndpointIsCoveredToo() throws Exception {
        String token = loginFreshUser("ratelimit-rag");

        mockMvc.perform(post("/api/knowledge-bases/123/ask")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"你好"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(7001));
    }

    @Test
    @DisplayName("不在清单里的路径完全不受影响（限流不该误伤普通读接口）")
    void ordinaryEndpointIsUnaffected() throws Exception {
        String token = loginFreshUser("ratelimit-plain");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 连续打十次也照样通——证明这个路径确实不在限流范围内
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    /**
     * ★ 这条守的是"路径清单不要用通配把聊天室的其他操作一起罩进来"。
     *
     * <p>清单初稿写的是 {@code /api/chat/**}——读历史消息、列会话、删会话、停止生成
     * 都会被算进**当日额度**。这类过度覆盖不会报错，只会让用户看到的
     * 「今日 N/200 次」不再等于"问了几次"，而且在他完全不花钱的操作上扣钱。
     * 所以这里拿最典型的三个只读/非模型路径反证：限额置 0（任何受限请求都会 429），
     * 它们必须照常放行。
     */
    @Test
    @DisplayName("★ /api/chat 下的非模型路径不占额度：读历史、列会话、停止生成都不该被算进去")
    void nonModelChatPathsAreNotCharged() throws Exception {
        String token = loginFreshUser("ratelimit-readonly");

        // 注意断言的是"不是 429"，而不是某个具体状态码：这几条路径各自的业务结果
        // （空列表 / 404 / 204）不是这条用例要钉的东西，钉的是"限流没走到这里"
        mockMvc.perform(get("/api/chat/messages/999999").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(org.hamcrest.Matchers.not(HttpStatus.TOO_MANY_REQUESTS.value())));

        mockMvc.perform(get("/api/chat/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(org.hamcrest.Matchers.not(HttpStatus.TOO_MANY_REQUESTS.value())));

        mockMvc.perform(post("/api/chat/stream/probe-request-id/stop")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(org.hamcrest.Matchers.not(HttpStatus.TOO_MANY_REQUESTS.value())));
    }

    @Test
    @DisplayName("改密码路径受限流约束（它是唯一能用已认证身份试原密码的入口）")
    void passwordEndpointIsRateLimited() throws Exception {
        String token = loginFreshUser("ratelimit-password");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"BrandNewPwd!99"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(7001));
    }

    @Test
    @DisplayName("未认证请求打到配额路径 → 401（限流不改变鉴权语义，不越权替认证作答）")
    void anonymousRequestIsStillUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    /** 每个用例用独立账号：冻结/计数都是用户维度的，共用账号会让用例互相干扰。 */
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
