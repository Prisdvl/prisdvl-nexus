package com.lky.nexus.app.observability;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.observability.mapper.ObservabilityCallMapper;
import com.lky.nexus.app.observability.model.entity.ObservabilityCallPO;
import com.lky.nexus.common.observe.CallTrace;
import com.lky.nexus.common.observe.CallTraceRecorder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 10.1 验收测试：五个只读观测端点。
 *
 * <p><b>为什么直接调 {@link CallTraceRecorder} 造数据而不是打真实模型</b>：
 * 要验证的是「明细 → 聚合 → 契约」这条链路。真实模型调用只会把不稳定因素
 * （网络、密钥、供应商的 token 计数口径）塞进断言里，却不多验证一行聚合逻辑。
 * 写入侧与 Advisor 的握手由 {@code UsageMeteringAdvisorTest} 负责，那里注入的是假 recorder。
 *
 * <p>造的三条调用刻意覆盖三件容易错的事：
 * <ol>
 *   <li><b>两次调用同属一个 requestId</b>（{@code req-chat}）—— requests 列表必须聚成一行，
 *       拆成两行就等于把"一次问答"报成了"两次"；</li>
 *   <li><b>一条调用没有 token</b>（失败的那次）—— 汇总不能因此变成 0，也不能当场 NPE；</li>
 *   <li><b>失败与成功混在同一窗口</b> —— 错误率与 costComplete 才都有东西可断言。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservabilityApiTest {

    private static final String PASSWORD = "Passw0rd!23";

    /** 与 {@code req-chat} 配对的消息元数据：检索摘要与工具摘要的真实来源。 */
    private static final String CHAT_METADATA = """
            {
              "toolCalls": [
                {"tool": "kb.search", "input": "{\\"query\\":\\"部署\\"}", "resultSummary": "3 条命中"}
              ],
              "citations": [
                {"documentName": "部署笔记.md", "headingPath": "# 部署"}
              ],
              "retrieval": {
                "knowledgeBaseIds": [9],
                "count": 2,
                "contextTokens": 1234,
                "truncated": false,
                "reranked": true,
                "empty": false,
                "hits": [
                  {"knowledgeBaseId": 9, "documentName": "部署笔记.md", "headingPath": "# 部署",
                   "score": 0.91, "snippet": "用 docker compose 起"}
                ]
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CallTraceRecorder callTraceRecorder;

    @Autowired
    private ObservabilityCallMapper callMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Test
    @DisplayName("落库端口：CallTrace 原样写进 observability_call")
    void traceRecorderPersistsOneRow() throws Exception {
        Session owner = registerAndLogin("obs-recorder");

        callTraceRecorder.record(new CallTrace("req-recorder", owner.userId(), "chat", "deepseek-chat",
                12, 34, 250L, 80L, true, null));

        List<ObservabilityCallPO> rows = callMapper.selectList(Wrappers.<ObservabilityCallPO>lambdaQuery()
                .eq(ObservabilityCallPO::getRequestId, "req-recorder"));
        assertThat(rows).hasSize(1);
        ObservabilityCallPO row = rows.getFirst();
        assertThat(row.getUserId()).isEqualTo(owner.userId());
        assertThat(row.getScene()).isEqualTo("chat");
        assertThat(row.getModel()).isEqualTo("deepseek-chat");
        assertThat(row.getTokensIn()).isEqualTo(12);
        assertThat(row.getTokensOut()).isEqualTo(34);
        assertThat(row.getLatencyMs()).isEqualTo(250L);
        assertThat(row.getFirstTokenMs()).isEqualTo(80L);
        assertThat(row.getSuccess()).isEqualTo(1);
        assertThat(row.getErrorCode()).isNull();
        // 落库实现自己补时间戳：advisor 那边没有"现在几点"的信息
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("overview：3 次调用 → 计数、token、错误率、平均延迟与成本都对得上")
    void overviewAggregatesThreeCalls() throws Exception {
        Session owner = registerAndLogin("obs-overview");
        seedCalls(owner.userId());

        String body = mockMvc.perform(get("/api/observability/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.windowHours").value(24))
                .andExpect(jsonPath("$.data.calls").value(3))
                // 失败那次没有 token：300 / 110 只来自两条成功调用，不会被清零
                .andExpect(jsonPath("$.data.tokensIn").value(300))
                .andExpect(jsonPath("$.data.tokensOut").value(110))
                .andExpect(jsonPath("$.data.tokens").value(410))
                .andExpect(jsonPath("$.data.failures").value(1))
                .andExpect(jsonPath("$.data.ragCalls").value(0))
                // 本用例不造工具调用记录：toolCalls 取 ai_tool_invocation，与模型调用无关
                .andExpect(jsonPath("$.data.toolCalls").value(0))
                .andExpect(jsonPath("$.data.costComplete").value(true))
                .andExpect(jsonPath("$.data.unpricedModels").isEmpty())
                // 1700ms / 3 次 = 566ms（整数毫秒，向下取整）
                .andExpect(jsonPath("$.data.avgLatencyMs").value(566))
                // 只有两条成功调用带首 Token 延迟：(120 + 130) / 2
                .andExpect(jsonPath("$.data.firstTokenMs").value(125))
                .andReturn().getResponse().getContentAsString();

        double errorRate = JsonPath.read(body, "$.data.errorRate");
        assertThat(errorRate).isCloseTo(1.0 / 3, within(0.0001));

        // (300 × 2.0 + 110 × 8.0) / 1_000_000 —— 单价来自 application-test.yml
        double estimatedCost = JsonPath.read(body, "$.data.estimatedCost");
        assertThat(estimatedCost).isCloseTo(0.00148, within(1e-9));
    }

    @Test
    @DisplayName("overview：窗口参数被夹到 1..720 小时，非法值回落默认 24h")
    void overviewClampsWindow() throws Exception {
        Session owner = registerAndLogin("obs-window");

        mockMvc.perform(get("/api/observability/overview")
                        .param("hours", "20000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowHours").value(720));

        mockMvc.perform(get("/api/observability/overview")
                        .param("hours", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowHours").value(1));
    }

    @Test
    @DisplayName("requests：同 requestId 聚成一行，scene / success 过滤生效，最新在前")
    void requestsAggregateByRequestIdAndFilter() throws Exception {
        Session owner = registerAndLogin("obs-requests");
        seedCalls(owner.userId());

        mockMvc.perform(get("/api/observability/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                // 两条调用同属 req-chat ⇒ 列表里只有一行、calls=2
                .andExpect(jsonPath("$.data.list[0].requestId").value("req-agent"))
                .andExpect(jsonPath("$.data.list[0].calls").value(1))
                .andExpect(jsonPath("$.data.list[0].success").value(false))
                .andExpect(jsonPath("$.data.list[0].errorCode").value(4002))
                .andExpect(jsonPath("$.data.list[1].requestId").value("req-chat"))
                .andExpect(jsonPath("$.data.list[1].calls").value(2))
                .andExpect(jsonPath("$.data.list[1].success").value(true));

        mockMvc.perform(get("/api/observability/requests")
                        .param("scene", "agent")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].requestId").value("req-agent"));

        mockMvc.perform(get("/api/observability/requests")
                        .param("success", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].requestId").value("req-agent"));

        mockMvc.perform(get("/api/observability/requests")
                        .param("success", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].requestId").value("req-chat"));
    }

    @Test
    @DisplayName("requests/{id}：带出该请求的检索摘要与工具摘要")
    void requestDetailCarriesRetrievalAndTools() throws Exception {
        Session owner = registerAndLogin("obs-detail");
        seedCalls(owner.userId());

        mockMvc.perform(get("/api/observability/requests/req-chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requestId").value("req-chat"))
                .andExpect(jsonPath("$.data.scene").value("chat"))
                .andExpect(jsonPath("$.data.model").value("deepseek-chat"))
                // 两轮调用 ⇒ callCount=2、逐轮明细两行、token 合计 410
                .andExpect(jsonPath("$.data.callCount").value(2))
                .andExpect(jsonPath("$.data.calls.length()").value(2))
                .andExpect(jsonPath("$.data.tokens").value(410))
                .andExpect(jsonPath("$.data.success").value(true))
                // 检索摘要来自 chat_message.metadata.retrieval
                .andExpect(jsonPath("$.data.retrieval.found").value(true))
                .andExpect(jsonPath("$.data.retrieval.knowledgeBaseIds[0]").value(9))
                .andExpect(jsonPath("$.data.retrieval.contextTokens").value(1234))
                .andExpect(jsonPath("$.data.retrieval.reranked").value(true))
                .andExpect(jsonPath("$.data.retrieval.hits[0].documentName").value("部署笔记.md"))
                .andExpect(jsonPath("$.data.retrieval.hits[0].score").value(0.91))
                // 工具摘要来自 chat_message.metadata.toolCalls
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("kb.search"))
                .andExpect(jsonPath("$.data.toolCalls[0].resultSummary").value("3 条命中"));
    }

    @Test
    @DisplayName("requests/{id}：不存在或别人的 requestId 一律 404 + 9101（不暴露存在性）")
    void requestDetailIsScopedToOwner() throws Exception {
        Session owner = registerAndLogin("obs-scope-owner");
        seedCalls(owner.userId());
        Session other = registerAndLogin("obs-scope-other");

        mockMvc.perform(get("/api/observability/requests/req-chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(9101));

        mockMvc.perform(get("/api/observability/requests/does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(9101));

        // 另一个用户的报表里不该出现这些调用
        mockMvc.perform(get("/api/observability/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calls").value(0));
    }

    @Test
    @DisplayName("models / trend：按模型聚合带成本，按小时分桶且空桶补零")
    void modelsAndTrend() throws Exception {
        Session owner = registerAndLogin("obs-models");
        seedCalls(owner.userId());

        mockMvc.perform(get("/api/observability/models")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].model").value("deepseek-chat"))
                .andExpect(jsonPath("$.data[0].calls").value(3))
                .andExpect(jsonPath("$.data[0].failures").value(1))
                .andExpect(jsonPath("$.data[0].priced").value(true))
                .andExpect(jsonPath("$.data[0].scenes").isArray());

        String trend = mockMvc.perform(get("/api/observability/trend")
                        .param("by", "hour")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by").value("hour"))
                .andExpect(jsonPath("$.data.points").isArray())
                .andReturn().getResponse().getContentAsString();

        // 三个调用的 token 合计必须回到趋势里（桶可能不止一个，求和才稳）
        java.util.List<Integer> tokens = JsonPath.read(trend, "$.data.points[*].tokens");
        assertThat(tokens.stream().mapToInt(Integer::intValue).sum()).isEqualTo(410);
        java.util.List<Integer> calls = JsonPath.read(trend, "$.data.points[*].calls");
        assertThat(calls.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 造数与登录
    // ------------------------------------------------------------------

    /**
     * 造三条调用 + 一条带检索/工具元数据的助手消息。
     *
     * <p><b>为什么这里直接写 mapper 而不是走 {@link CallTraceRecorder}</b>：落库实现自己
     * 补 {@code created_at = now()}，那样三条调用会落在同一毫秒里——"requests 最新在前"
     * 就变成靠插入顺序碰运气。往对比的是聚合逻辑，时间必须由用例说了算。
     * recorder 的落库行为另有 {@link #traceRecorderPersistsOneRow()} 单独覆盖。
     *
     * <p>时间刻意错开：requests 列表按最近一次调用倒序，错开才能断言"最新在前"。
     */
    private void seedCalls(long userId) {
        LocalDateTime now = LocalDateTime.now();
        // req-chat：两轮调用（非流式对话的典型形态）
        insertCall("req-chat", userId, "chat", 100, 50, 300L, 120L, true, null, now.minusMinutes(20));
        insertCall("req-chat", userId, "chat", 200, 60, 400L, 130L, true, null, now.minusMinutes(15));
        // req-agent：一轮失败，没有 token（供应商没回传）
        insertCall("req-agent", userId, "agent", null, null, 1000L, null, false, 4002, now.minusMinutes(5));

        ChatMessagePO message = new ChatMessagePO();
        message.setConversationId(1L);
        message.setUserId(userId);
        message.setRole("ASSISTANT");
        message.setStatus("DONE");
        message.setRequestId("req-chat");
        message.setMetadata(CHAT_METADATA);
        message.setCreatedAt(now.minusMinutes(20));
        chatMessageMapper.insert(message);
    }

    private void insertCall(String requestId, long userId, String scene, Integer tokensIn, Integer tokensOut,
                            long latencyMs, Long firstTokenMs, boolean success, Integer errorCode,
                            LocalDateTime at) {
        ObservabilityCallPO row = new ObservabilityCallPO();
        row.setRequestId(requestId);
        row.setUserId(userId);
        row.setScene(scene);
        row.setModel("deepseek-chat");
        row.setTokensIn(tokensIn);
        row.setTokensOut(tokensOut);
        row.setLatencyMs(latencyMs);
        row.setFirstTokenMs(firstTokenMs);
        row.setSuccess(success ? 1 : 0);
        row.setErrorCode(errorCode);
        row.setCreatedAt(at);
        callMapper.insert(row);
    }

    private Session registerAndLogin(String username) throws Exception {
        // email 是必填项：注册接口要求用户名、密码、邮箱三者齐全（见 UserService.register）
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                // 同一个用户名在多个用例里复用时会撞 409；两者都算"账号已就绪"
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(loginBody, "$.data.accessToken");

        String meBody = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(JsonPath.read(meBody, "$.data.id").toString());
        return new Session(token, userId);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Session(String token, long userId) {
    }
}
