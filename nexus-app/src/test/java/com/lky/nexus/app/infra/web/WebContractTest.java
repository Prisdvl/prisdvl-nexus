package com.lky.nexus.app.infra.web;

import com.lky.nexus.common.constant.NexusHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 0.5 验收测试：Web 层契约。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>成功响应固定为 {@code {code:0, message:"success", data:{...}}}；</li>
 *   <li>每个响应都带 {@code X-Request-Id}；合法的上游 ID 被透传，非法的被替换（日志注入防护）；</li>
 *   <li>业务异常 → 对应 HTTP 状态码 + 业务码（3001 → 404，3006 → 413）；</li>
 *   <li>参数校验失败 → 400 + 1001，且带字段级提示；</li>
 *   <li>未预期异常 → 500 + 9999，且不泄漏堆栈；</li>
 *   <li>Long 主键序列化为字符串，基本类型计数保持数字（前端精度问题）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("成功响应：统一返回体 + X-Request-Id 响应头")
    void successResponseFollowsContract() throws Exception {
        mockMvc.perform(get("/test-only/ok/1"))
                .andExpect(status().isOk())
                .andExpect(header().exists(NexusHeaders.REQUEST_ID))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.name").value("demo"));
    }

    @Test
    @DisplayName("Long 主键序列化为字符串，基本类型计数保持数字")
    void longIdIsSerializedAsStringButCountsStayNumeric() throws Exception {
        mockMvc.perform(get("/test-only/ok/2104750903666438145"))
                .andExpect(status().isOk())
                // 雪花 ID 必须带引号，否则前端 JS 会丢精度
                .andExpect(jsonPath("$.data.id").value("2104750903666438145"))
                // 计数保持数字形态，前端可直接做算术
                .andExpect(jsonPath("$.data.totalTokens").value(42))
                .andExpect(jsonPath("$.data.chunkCount").value(7));
    }

    @Test
    @DisplayName("合法上游 Request-Id 被透传（便于跨服务串联）")
    void safeUpstreamRequestIdIsPropagated() throws Exception {
        mockMvc.perform(get("/test-only/ok/1").header(NexusHeaders.REQUEST_ID, "upstream-trace-001"))
                .andExpect(header().string(NexusHeaders.REQUEST_ID, "upstream-trace-001"));
    }

    @Test
    @DisplayName("非法 Request-Id 被替换：防日志注入（换行符伪造日志行）")
    void unsafeRequestIdIsReplaced() throws Exception {
        String injected = "bad\n2026-01-01 ERROR fake log line";
        String actual = mockMvc.perform(get("/test-only/ok/1").header(NexusHeaders.REQUEST_ID, injected))
                .andReturn().getResponse().getHeader(NexusHeaders.REQUEST_ID);

        assertThat(actual).isNotNull();
        assertThat(actual).isNotEqualTo(injected);
        assertThat(actual).matches("^[A-Za-z0-9_-]{1,64}$");
    }

    @Test
    @DisplayName("业务异常：3001 知识库不存在 → HTTP 404 + body code 3001")
    void businessExceptionMapsToHttpStatusAndCode() throws Exception {
        mockMvc.perform(get("/test-only/business-error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001))
                .andExpect(jsonPath("$.message").value("知识库不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("业务异常：自定义提示信息被保留（带上下文更利于排查）")
    void businessExceptionKeepsCustomMessage() throws Exception {
        mockMvc.perform(get("/test-only/business-error-custom"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value(3006))
                .andExpect(jsonPath("$.message").value("文件 51MB 超出 50MB 限制"));
    }

    @Test
    @DisplayName("参数校验失败：HTTP 400 + code 1001 + 字段级提示")
    void validationFailureMapsTo400() throws Exception {
        mockMvc.perform(post("/test-only/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name 不能为空")));
    }

    @Test
    @DisplayName("未预期异常：HTTP 500 + code 9999，且不泄漏异常堆栈")
    void unexpectedExceptionMapsTo500WithoutLeakingDetails() throws Exception {
        String body = mockMvc.perform(get("/test-only/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(9999))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("IllegalStateException").doesNotContain("模拟未预期异常");
    }

    /**
     * 测试专用安全过滤器链：只对 {@code /test-only/**} 放行。
     *
     * <p>为什么需要它：Step 1.1 引入 Spring Security 后，主链（{@code @Order(2)}）会要求所有请求
     * 都带 JWT，这些"契约测试"用的假接口就会一律 401。这里用 {@code @Order(1)} +
     * {@code securityMatcher} 只放行测试路径，<b>既不改动生产配置，也不会在生产包里出现</b>
     * （{@code @TestConfiguration} 嵌套类只在测试编译产物中）。
     */
    @TestConfiguration
    static class TestOnlySecurityConfig {

        @Bean
        @Order(1)
        SecurityFilterChain testOnlySecurityFilterChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/test-only/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }
}
