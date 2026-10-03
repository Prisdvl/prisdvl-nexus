package com.lky.nexus.app.observe;

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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /actuator/metrics}（Phase 5.9 六）：把"记了账"变成"运维真能读到"。
 *
 * <p><b>为什么必须有一条走 MockMvc 的测试</b>：{@link ObservabilityRegistry} 的单元测试只能证明
 * "记账对象记得住"。从"记了账"到"在 {@code /actuator/metrics} 上读得到"，中间还有三层拼接——
 * 注册表是否接上了容器里的 {@code MeterRegistry}、指标名是否被端点原样暴露、
 * 指标端点是否恰好落在"需认证"的那一侧。这三层任意一层断了，现象都只是
 * "那个页面打开是空的"或"401"，不会报错。下面每条各钉一次。
 *
 * <p>驱动方式刻意选"一次真实 HTTP 请求 + 一次真实的业务拒绝"：给 RAG 会话调非流式接口
 * （{@code POST /api/chat/conversations/{id}/messages}）。这条请求在计数之后、<b>调用模型之前</b>
 * 就被 {@code 8003} 挡下，因此既真的走了控制器与业务层，又不会向测试 profile 里那个远端
 * {@code base-url} 发一次真实网络请求。反过来说，本用例<b>不能</b>用来验证
 * "一次成功的对话会记 chat.latency"——那条路会真的打模型，属于真机 E2E 的范围，
 * 这里不假装覆盖了它。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorMetricsTest {

    private static final String PASSWORD = "ActuatorMetrics!Pwd1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("指标不是公开端点：不带令牌读 401（它暴露模型名与调用量，不能匿名看）")
    void metricsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        // 顺带钉住另一个方向的边界：健康检查必须保持公开，
        // 否则容器 healthcheck 与编排探针会一起失效（5.7 的部署闸门依赖它）
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 一次真实请求走完业务层之后，/actuator/metrics 上读得到 nexus.chat.requests")
    void realRequestMovesTheChatRequestsCounter() throws Exception {
        String token = loginFreshUser("metrics-chat");
        String conversationId = createRagConversation(token, "指标测试会话");

        // 同一个 Spring 上下文被多个测试类共享，累计值可能已被别人抬过。
        // 因此断言的是**增量**而不是绝对值：绝对值断言会在测试顺序变化时莫名其妙地红
        double before = counter("nexus.chat.requests", token);
        double errorsBefore = counter("nexus.errors", token);

        // RAG 会话走非流式接口 → 8003。计数发生在归属校验之后、模型调用之前，
        // 所以这是一次"真的进了业务层"的请求，而不是对着注册表戳一下
        mockMvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"缓存击穿怎么解决？"}
                                """))
                // 409 而不是 400：8003 是"资源当前状态与该操作冲突"（会话是 RAG 模式），
                // 映射见 ErrorCodeHttpStatusMapper
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(8003));

        assertThat(counter("nexus.chat.requests", token)).isEqualTo(before + 1.0);
        // 预期内的业务分支（会话模式不匹配）**不算错误**：若它进了 errors，
        // 线上告警会被用户的操作失误淹没，而真正的问题反而看不见
        assertThat(counter("nexus.errors", token)).isEqualTo(errorsBefore);
    }

    @Test
    @DisplayName("指标列表里带 nexus. 前缀，名字与 ObservabilityRegistry 里定义的一致（照名字能找得到）")
    void metricNamesAreListedUnderTheNexusPrefix() throws Exception {
        String token = loginFreshUser("metrics-list");
        // 先产生一个指标，免得断言建立在"本来就没有"的空列表上
        String conversationId = createRagConversation(token, "指标列表会话");
        mockMvc.perform(post("/api/chat/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"随便问问"}
                                """))
                .andExpect(status().isConflict());

        String body = mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> names = JsonPath.read(body, "$.names");
        // 键是 chat.requests、指标名是 nexus.chat.requests：这条断言就是"映射真的存在"的证据
        assertThat(names).contains("nexus.chat.requests");
    }

    // ------------------------------------------------------------------

    /**
     * 读一项指标的 COUNT。
     *
     * <p>读不到（404）按 {@code 0} 处理：Micrometer 的计数器是<b>首次记录时才存在</b>的，
     * "从来没有 increment 过"与"指标是 0"在语义上确实是同一件事。
     * 若把 404 当成失败，本用例就会依赖"恰好有别的测试先动过这个计数器"——那是脆弱测试。
     */
    private double counter(String metricName, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/metrics/" + metricName)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        if (result.getResponse().getStatus() == 404) {
            return 0.0;
        }
        assertThat(result.getResponse().getStatus())
                .as("读指标 %s 应当是 200 或 404（未注册），而不是别的状态", metricName)
                .isEqualTo(200);

        List<Number> values = JsonPath.read(result.getResponse().getContentAsString(),
                "$.measurements[?(@.statistic == 'COUNT')].value");
        assertThat(values).as("指标 %s 应恰好有一个 COUNT 测量值", metricName).hasSize(1);
        return values.get(0).doubleValue();
    }

    private String createRagConversation(String token, String title) throws Exception {
        // 不带 kbIds：本用例要的是"进业务层"，不是"检索成功"。
        // RAG 的未绑定校验发生在计数之后、模式校验之前，因此不影响计数
        String created = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","mode":"RAG"}
                                """.formatted(title)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.id");
    }

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
