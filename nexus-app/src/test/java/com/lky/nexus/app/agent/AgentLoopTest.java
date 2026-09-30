package com.lky.nexus.app.agent;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 真实链路守卫测试：模型是否真的会调用工具。
 *
 * <p>需要 {@code AI_CHAT_API_KEY}（真实模型 + 网关）。守卫的目的是<b>如实暴露</b>：
 * 如果当前网关/模型不支持函数调用，这条测试会失败——那是真实发现，
 * 而不是被"装饰成能跑"掩盖掉。测试环境检索基础设施缺失时工具会返回"0 条结果"，
 * 但这不影响断言（工具被调用本身就说明循环成立了）。
 */
@EnabledIfEnvironmentVariable(named = "AI_CHAT_API_KEY", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentLoopTest {

    private static final String USERNAME = "agent-loop";
    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("涉及知识库的问题必须先调用 knowledge_search，再给出回答")
    void agentUsesToolThenAnswers() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(USERNAME, PASSWORD)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(loginBody, "$.data.accessToken");

        String body = mockMvc.perform(post("/api/agent/ask")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"我的知识笔记里有哪些与缓存相关的内容？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        boolean usedTools = JsonPath.read(body, "$.data.usedTools");
        String answer = JsonPath.read(body, "$.data.answer");
        java.util.List<Object> toolCalls = JsonPath.read(body, "$.data.toolCalls");

        assertThat(usedTools)
                .as("涉及知识库的问题必须触发工具调用（若模型网关不支持函数调用，这条会如实失败）")
                .isTrue();
        assertThat(toolCalls).isNotEmpty();
        assertThat(JsonPath.<String>read(body, "$.data.toolCalls[0].tool"))
                .isEqualTo("knowledge_search");
        assertThat(answer).isNotBlank();
    }
}