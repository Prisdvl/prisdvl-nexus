package com.lky.nexus.app.chat;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 1.6 验收测试（离线部分）：流式接口的准入与错误处理。
 *
 * <p>这些用例在<b>打开 SSE 之前</b>就会失败返回，因此不需要调用模型、不消耗 Token。
 * 真正的流式行为（事件顺序、内容拼接、落库、停止）由 {@code ChatStreamLiveTest} 用真实 HTTP 验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatStreamContractTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未认证访问流式接口 → 401（SSE 也不能例外）")
    void streamRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":1,"content":"你好"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("内容为空 → 400 + 1001（校验在开流之前完成）")
    void blankContentRejected() throws Exception {
        String token = registerAndLogin("stream-blank");

        mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":1,"content":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("会话不存在 → 404 + 8001（错误以统一 JSON 返回，而不是开一条 SSE 再报错）")
    void unknownConversationRejected() throws Exception {
        String token = registerAndLogin("stream-unknown");

        mockMvc.perform(post("/api/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":123456789,"content":"你好"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(8001));
    }

    @Test
    @DisplayName("停止一个不存在的流 → 200 + cancelled=false（幂等，不算错误）")
    void stoppingUnknownStreamIsIdempotent() throws Exception {
        String token = registerAndLogin("stream-stop-unknown");

        mockMvc.perform(post("/api/chat/stream/not-a-real-request-id/stop")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cancelled").value(false));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, PASSWORD)));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }
}
