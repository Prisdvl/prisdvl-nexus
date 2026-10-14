package com.lky.nexus.app.auth.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resend 发信单元测试：Mock 本地 HTTP，绝不调用真实 Resend API。
 */
@ExtendWith(OutputCaptureExtension.class)
class ResendEmailSenderTest {

    /** 仅测试用假密钥，非真实凭据。 */
    private static final String FAKE_API_KEY = "re_test_fake_key_do_not_use";
    private static final String FROM = "Prisdvl-Nexus <onboarding@resend.dev>";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("成功：Authorization / from / to / subject / html / text 正确")
    void sendBuildsCorrectRequestAndSucceeds() throws Exception {
        AtomicReference<Captured> captured = new AtomicReference<>();
        startServer((ex, body) -> {
            captured.set(new Captured(
                    ex.getRequestMethod(),
                    ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("Authorization"),
                    ex.getRequestHeaders().getFirst("Content-Type"),
                    ex.getRequestHeaders().getFirst("User-Agent"),
                    body));
            writeJson(ex, 200, "{\"id\":\"msg_test\"}");
        });

        sender().sendCode("user@example.com", "654321", EmailPurpose.VERIFY_EMAIL, Duration.ofMinutes(5));

        Captured req = captured.get();
        assertThat(req).isNotNull();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/emails");
        assertThat(req.authorization()).isEqualTo("Bearer " + FAKE_API_KEY);
        assertThat(req.contentType()).contains("application/json");
        assertThat(req.userAgent()).isEqualTo("Prisdvl-Nexus/1.0");

        JsonNode json = MAPPER.readTree(req.body());
        assertThat(json.get("from").asText()).isEqualTo(FROM);
        assertThat(json.get("to").isArray()).isTrue();
        assertThat(json.get("to").get(0).asText()).isEqualTo("user@example.com");
        assertThat(json.get("subject").asText()).isEqualTo("Prisdvl-Nexus 邮箱验证码");
        assertThat(json.get("text").asText())
                .contains("654321")
                .contains("5 分钟")
                .contains("只能使用一次")
                .contains("请勿向他人透露");
        assertThat(json.get("html").asText())
                .contains("654321")
                .contains("5 分钟")
                .contains("只能使用一次");
    }

    @Test
    @DisplayName("API 成功响应（2xx）不抛异常")
    void http2xxIsSuccess() throws Exception {
        startServer((ex, body) -> writeJson(ex, 200, "{\"id\":\"ok\"}"));
        sender().sendCode("a@b.com", "111111", EmailPurpose.VERIFY_EMAIL, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("API 401 认证失败 → BusinessException，不伪装成功")
    void http401Fails() throws Exception {
        startServer((ex, body) -> writeJson(ex, 401, "{\"message\":\"invalid api key\"}"));
        assertSendFails(sender());
    }

    @Test
    @DisplayName("API 429 限流 → BusinessException")
    void http429Fails() throws Exception {
        startServer((ex, body) -> writeJson(ex, 429, "{\"message\":\"rate limited\"}"));
        assertSendFails(sender());
    }

    @Test
    @DisplayName("API 500 → BusinessException")
    void http500Fails() throws Exception {
        startServer((ex, body) -> writeJson(ex, 500, "{\"message\":\"oops\"}"));
        assertSendFails(sender());
    }

    @Test
    @DisplayName("连接失败 → BusinessException，不伪装成功")
    void connectionFailureFails() {
        // 端口 1 通常无服务监听
        ResendEmailSender broken = new ResendEmailSender(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(),
                MAPPER,
                FAKE_API_KEY,
                FROM,
                "http://127.0.0.1:1",
                Duration.ofMillis(300));
        assertSendFails(broken);
    }

    @Test
    @DisplayName("读超时 → BusinessException")
    void readTimeoutFails() throws Exception {
        startServer((ex, body) -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(ex, 200, "{\"id\":\"late\"}");
        });
        ResendEmailSender slow = new ResendEmailSender(
                HttpClient.newHttpClient(),
                MAPPER,
                FAKE_API_KEY,
                FROM,
                baseUrl(),
                Duration.ofMillis(100));
        assertSendFails(slow);
    }

    @Test
    @DisplayName("异常消息与日志不泄露 API Key；失败日志不含验证码")
    void failuresDoNotLeakSecrets(CapturedOutput output) throws Exception {
        startServer((ex, body) -> writeJson(ex, 401, "{\"message\":\"nope " + FAKE_API_KEY + "\"}"));

        assertThatThrownBy(() ->
                sender().sendCode("user@example.com", "888888", EmailPurpose.VERIFY_EMAIL, Duration.ofMinutes(5)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    String msg = ((BusinessException) ex).getMessage();
                    assertThat(msg).doesNotContain(FAKE_API_KEY);
                    assertThat(msg).doesNotContain("888888");
                    assertThat(msg).contains("发送失败");
                });

        String all = output.getAll();
        assertThat(all).doesNotContain(FAKE_API_KEY);
        assertThat(all).doesNotContain("888888");
        assertThat(all).doesNotContain("Bearer " + FAKE_API_KEY);
    }

    @Test
    @DisplayName("配置缺失 api-key / from → 构造失败关闭")
    void missingConfigFailsClosed() {
        NexusEmailProperties.Resend empty = new NexusEmailProperties.Resend();
        empty.setFrom(FROM);
        assertThatThrownBy(() -> new ResendEmailSender(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");

        NexusEmailProperties.Resend noFrom = new NexusEmailProperties.Resend();
        noFrom.setApiKey(FAKE_API_KEY);
        assertThatThrownBy(() -> new ResendEmailSender(noFrom))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("from");
    }

    @Test
    @DisplayName("Resend 配置 toString 不暴露密钥")
    void propertiesToStringRedactsApiKey() {
        NexusEmailProperties.Resend resend = new NexusEmailProperties.Resend();
        resend.setApiKey(FAKE_API_KEY);
        resend.setFrom(FROM);
        assertThat(resend.toString())
                .contains("(redacted)")
                .doesNotContain(FAKE_API_KEY);
    }

    @Test
    @DisplayName("用途模板隔离：注册 / 重置主题不同")
    void purposeTemplatesDiffer() {
        assertThat(ResendEmailSender.subjectOf(EmailPurpose.REGISTER)).contains("注册");
        assertThat(ResendEmailSender.subjectOf(EmailPurpose.RESET_PASSWORD)).contains("密码重置");
        assertThat(ResendEmailSender.textBodyOf("999999", EmailPurpose.RESET_PASSWORD, Duration.ofMinutes(5)))
                .contains("重置密码")
                .contains("999999");
    }

    @Test
    @DisplayName("不自动重试：失败只打一次 HTTP")
    void noAutomaticRetryOnFailure() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer((ex, body) -> {
            hits.incrementAndGet();
            writeJson(ex, 503, "{\"message\":\"unavailable\"}");
        });
        assertSendFails(sender());
        assertThat(hits.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private ResendEmailSender sender() {
        return new ResendEmailSender(
                HttpClient.newHttpClient(),
                MAPPER,
                FAKE_API_KEY,
                FROM,
                baseUrl(),
                Duration.ofSeconds(2));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void assertSendFails(ResendEmailSender emailSender) {
        assertThatThrownBy(() ->
                emailSender.sendCode("user@example.com", "123456", EmailPurpose.VERIFY_EMAIL, Duration.ofMinutes(5)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
                    assertThat(be.getMessage()).doesNotContain(FAKE_API_KEY);
                });
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            handler.handle(exchange, new String(body, StandardCharsets.UTF_8));
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException;
    }

    private record Captured(String method, String path, String authorization, String contentType,
                            String userAgent, String body) {
    }
}
