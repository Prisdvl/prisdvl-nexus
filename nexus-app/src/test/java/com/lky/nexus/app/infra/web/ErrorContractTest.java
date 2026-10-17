package com.lky.nexus.app.infra.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错误码 ↔ HTTP 状态的契约测试（Phase 5.2 / P1-5、P1-10）。
 *
 * <p>为什么需要"探针端点"：这两类异常在单元测试里都不容易真实触发——
 * <ul>
 *   <li>{@link ToolCallLimitExceededException} 只有真跑 Agent 工具循环才会抛，
 *       而测试环境没有可用模型（不能靠"给测试放宽断言"绕过，那等于没测）；</li>
 *   <li>{@link MaxUploadSizeExceededException} 在真实链路里由容器在解析 multipart 时抛，
 *       MockMvc 的模拟请求不经过容器解析（那条由 {@code UploadSizeLimitHttpTest}
 *       用真实 Tomcat + 真实上传覆盖）。</li>
 * </ul>
 * 探针控制器只做一件事：抛这两个异常。它验的正是本测试要问的问题——
 * <b>异常类型有没有被 {@code GlobalExceptionHandler} 接住，接住后给的是什么码、什么状态码、什么话</b>。
 * 这是"契约测试"而不是"业务测试"，所以刻意不碰任何业务链路。
 *
 * <p>断言里同时要求"话里带具体数字"（工具名 / 上限值 / 配置的体积上限）：
 * 只断言错误码会放过"码对了但文案是糊的"这种情况，而那正是这次要修的东西。
 */
@SpringBootTest(properties = {
        "spring.servlet.multipart.max-file-size=3MB",
        "spring.servlet.multipart.max-request-size=5MB"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("错误契约：异常类型 → 业务码 → HTTP 状态码 → 用户看到的话")
class ErrorContractTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("工具轮数超限（单工具）→ 5002 + 工具名与上限，而不是 4001「模型调用失败」")
    void toolCallLimitIsMappedTo5002() throws Exception {
        String token = registerAndLogin("err-tool-limit");

        mockMvc.perform(get("/api/probe/tool-limit").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(5002))
                .andExpect(jsonPath("$.message").value(
                        allOf(containsString("knowledge_search"), containsString("40"))));
    }

    @Test
    @DisplayName("工具轮数超限（合计）→ 5002 + 合计上限，文案不提工具名")
    void totalToolCallLimitIsMappedTo5002() throws Exception {
        String token = registerAndLogin("err-tool-total");

        mockMvc.perform(get("/api/probe/tool-limit-total").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(5002))
                .andExpect(jsonPath("$.message").value(containsString("150")));
    }

    @Test
    @DisplayName("上传超限 → 413 + 3006，文案里是**配置的**上限（3MB/5MB），不是写死的 200MB")
    void uploadSizeExceededIsMappedTo413() throws Exception {
        String token = registerAndLogin("err-upload");

        mockMvc.perform(get("/api/probe/upload-too-large").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value(3006))
                .andExpect(jsonPath("$.message").value(
                        allOf(containsString("3MB"), containsString("5MB"))));
    }

    // ------------------------------------------------------------------

    @TestConfiguration
    static class ProbeEndpoints {

        /**
         * 必须用 {@code @Bean} 注册，不能只写 {@code @RestController}：
         * 测试类不在组件的扫描路径上，光加注解这个控制器根本不会被实例化，
         * 请求会走到 404——那时候测试"通过"的含义就彻底变了
         * （变成"404 也算"或者干脆断言失败），不是我们想验的东西。
         */
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /** 只用来抛异常，不碰任何业务。 */
    @RestController
    static class ProbeController {

        @GetMapping("/api/probe/tool-limit")
        void toolLimit() {
            throw new ToolCallLimitExceededException("knowledge_search", 40, null);
        }

        @GetMapping("/api/probe/tool-limit-total")
        void toolLimitTotal() {
            throw new ToolCallLimitExceededException(null, 150, null);
        }

        @GetMapping("/api/probe/upload-too-large")
        void uploadTooLarge() {
            throw new MaxUploadSizeExceededException(3 * 1024 * 1024);
        }
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s","email":"%s@example.com"}
                        """.formatted(username, PASSWORD, username)));

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
