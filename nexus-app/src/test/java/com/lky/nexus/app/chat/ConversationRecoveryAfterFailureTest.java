package com.lky.nexus.app.chat;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同一会话异常后恢复的自动化测试。
 *
 * <p>验证：
 * <ol>
 *   <li>第一次模型调用抛异常 → 请求失败，写 FAILED 消息；</li>
 *   <li>同一会话第二次调用成功 → 不残留生成状态，记忆不污染；</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("同一会话异常后恢复")
class ConversationRecoveryAfterFailureTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiModelProperties modelProperties;

    @MockitoBean
    private ChatClientFactory chatClientFactory;

    private ChatModel stub;

    @BeforeEach
    void stubModel() {
        when(chatClientFactory.configuredRoles()).thenReturn(modelProperties.getRoles().keySet());
        stub = mock(ChatModel.class);
        when(stub.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(chatClientFactory.get(anyString(), any())).thenReturn(org.springframework.ai.chat.client.ChatClient.builder(stub).build());
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().build());
    }

    @Test
    @DisplayName("第一次模型调用失败后，同一会话第二次调用成功")
    void recoverAfterModelFailure() throws Exception {
        String token = registerAndLogin("recovery-test");
        String conversationId = createConversation(token);

        // 第一次：模型抛异常（模拟 HTTP 400）
        when(stub.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("400 Bad Request: model not found"));

        // 第一次请求应失败（HTTP 200 统一包装 code!=0 或 503 SERVICE_UNAVAILABLE）
        var firstResult = mockMvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"第一次问的问题\"}"))
                .andReturn();
        var firstStatus = firstResult.getResponse().getStatus();
        var firstResp = firstResult.getResponse().getContentAsString();

        // 应该是 200（统一包装 code!=0）或 503（BusinessException 直接映射）
        assertThat(firstStatus).isIn(200, 503);
        if (firstStatus == 200) {
            Integer firstCode = com.jayway.jsonpath.JsonPath.read(firstResp, "$.code");
            assertThat(firstCode).as("第一次模型调用失败，code 不应为 0").isNotEqualTo(0);
        }

        // 验证消息：用户消息 + FAILED assistant 消息
        var messages = mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(messages).contains("第一次问的问题");
        assertThat(messages).contains("FAILED");

        // 第二次：模型恢复正常
        when(stub.call(any(Prompt.class)))
                .thenReturn(response("这是正常回答。"));

        // 第二次请求应成功
        mockMvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"第二次问的问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("这是正常回答。"));

        // 验证消息总数：2 条用户 + 2 条 assistant（FAILED + DONE）
        var finalMessages = mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(finalMessages).contains("第一次问的问题");
        assertThat(finalMessages).contains("第二次问的问题");
        assertThat(finalMessages).contains("这是正常回答。");
        assertThat(finalMessages).contains("FAILED");
        // 没有重复的用户消息
        assertThat(finalMessages.split("第一次问的问题")).hasSize(2);
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"" + username + "\",\"email\":\"" + username + "@example.com\"}"))
                .andExpect(status().isOk());
        var resp = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(resp, "$.data.accessToken");
    }

    private String createConversation(String token) throws Exception {
        var resp = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"恢复测试\",\"mode\":\"PLAIN\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(resp, "$.data.id");
    }
}
