package com.lky.nexus.ai.model;

import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.common.api.ErrorCode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型网关失败路径的集成测试：真起一个 {@link MockWebServer}，让 {@link OpenAiChatModel}
 * 以真实 HTTP 调用打到它上面。
 *
 * <p><b>关于耗时</b>：openai-java SDK 默认 maxRetries=2，对 429/5xx 会自动退避重试。
 * 用 {@link okhttp3.mockwebserver.Dispatcher} 让 server 对每次重试都返回同一状态码，
 * 这样 SDK 重试耗尽后抛对应的具体异常。read-timeout 真 HTTP 测试最慢（SDK 重试 3 次每次都等），
 * 因此把"超时分类"拆成两层：
 * <ul>
 *   <li>真 HTTP 验证"确实会超时、不挂死"——用 2s timeout，容忍 SDK 重试放大到 ~30s；</li>
 *   <li>分类正确性用纯单元测试（直接抛 {@link SocketTimeoutException}），不发网络。</li>
 * </ul>
 */
@DisplayName("OpenAiChatModel 真实 HTTP 失败路径")
class ChatModelFailurePathTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private OpenAiChatModel buildModel(Duration timeout) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("test-model")
                .baseUrl(server.url("/v1").toString())
                .apiKey("sk-test")
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(builder -> builder.timeout(timeout))
                .build();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "0")
                .setBody(body);
    }

    /** 让 server 永远返回指定响应（应对 SDK 自动重试）。 */
    private void alwaysReturn(MockResponse resp) {
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return resp;
            }
        });
    }

    @Test
    @DisplayName("400：立刻返回（<5s），分类为 MODEL_BAD_REQUEST 且不可重试")
    void badRequestFailsFastAndIsClassifiedAsBadRequest() {
        server.enqueue(json(400,
                "{\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(15));

        long start = System.nanoTime();
        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.MODEL_BAD_REQUEST);
                    assertThat(f.retryable()).isFalse();
                });
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ms).as("4xx must fail fast").isLessThan(5_000L);
    }

    @Test
    @DisplayName("401：分类为 MODEL_AUTH_FAILED 且不可重试")
    void unauthorizedIsClassifiedAsAuthFailed() {
        server.enqueue(json(401,
                "{\"error\":{\"message\":\"invalid api key\",\"type\":\"invalid_request_error\"}}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(15));

        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    @DisplayName("403：分类为 MODEL_AUTH_FAILED")
    void forbiddenIsClassifiedAsAuthFailed() {
        server.enqueue(json(403,
                "{\"error\":{\"message\":\"permission denied\",\"type\":\"invalid_request_error\"}}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(15));

        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.MODEL_AUTH_FAILED);
                });
    }

    @Test
    @DisplayName("429 通用限流：分类为 RATE_LIMITED 且可重试")
    void rateLimitIsClassifiedAsRetryable() {
        alwaysReturn(json(429,
                "{\"error\":{\"message\":\"rate limit exceeded\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit_reached\"}}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(15));

        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(f.retryable()).isTrue();
                });
    }

    @Test
    @DisplayName("429 + insufficient_quota：识别为额度耗尽，不可重试（不是普通限流）")
    void quotaExhaustedOn429IsNotRetryable() {
        alwaysReturn(json(429,
                "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(15));

        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.QUOTA_EXHAUSTED);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    @DisplayName("402 + insufficient_user_quota（DeepSeek 余额不足）：识别为额度耗尽")
    void deepSeekInsufficientBalanceIsQuotaExhausted() {
        alwaysReturn(json(402,
                "{\"error\":{\"message\":\"Insufficient balance\",\"type\":\"invalid_request_error\",\"code\":\"insufficient_user_quota\"}}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(15));

        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.QUOTA_EXHAUSTED);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    @DisplayName("500：分类为 MODEL_UNAVAILABLE 且可重试")
    void serverErrorIsClassifiedAsUnavailable() {
        alwaysReturn(json(500, "{\"error\":{\"message\":\"internal boom\",\"type\":\"server_error\",\"code\":\"internal_error\"}}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(15));

        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
                    assertThat(f.retryable()).isTrue();
                });
    }

    @Test
    @DisplayName("连接失败：关掉 server 后连不上，分类为 MODEL_UNAVAILABLE 且快速失败")
    void connectionFailureIsClassifiedAsUnavailable() throws IOException {
        int port = server.getPort();
        server.shutdown();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("test-model")
                .baseUrl("http://127.0.0.1:" + port + "/v1")
                .apiKey("sk-test")
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(b -> b.timeout(Duration.ofSeconds(5)))
                .build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> model.call("hello"))
                .satisfies(error -> {
                    FailureClassifier.Failure f = FailureClassifier.classify(error);
                    assertThat(f.code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);
                    assertThat(f.retryable()).isTrue();
                });
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ms).isLessThan(10_000L);
    }

    @Test
    @DisplayName("callTimeout：server 延迟响应，client 在 callTimeout 内失败（不挂死）")
    void callTimeoutFailsWithinBoundedTime() {
        // server 接受连接但延迟 10s 才回，client callTimeout=2s 应在 2s 触发；
        // SDK 默认重试 2 次，总耗时 ~2*3 + backoff ≈ 10s，远好于旧版"不 enqueue"导致的 ~240s。
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(10, java.util.concurrent.TimeUnit.SECONDS)
                .setBody("{}"));
        OpenAiChatModel model = buildModel(Duration.ofSeconds(2));

        long start = System.nanoTime();
        Throwable thrown = null;
        try {
            model.call("hello");
        } catch (Throwable t) {
            thrown = t;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(thrown).as("must fail, not hang").isNotNull();
        assertThat(ms).as("read timeout must fire within bounded time (was 180s+ before fix)")
                .isLessThan(30_000L);
        FailureClassifier.Failure f = FailureClassifier.classify(thrown);
        assertThat(f.retryable()).isTrue();
        // callTimeout 触发后 SDK 包装为 OpenAIIoException，分类到 TIMEOUT 或 UNAVAILABLE 都算合理
        assertThat(f.code()).isIn(ErrorCode.MODEL_TIMEOUT, ErrorCode.MODEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("超时分类（纯单元）：SocketTimeoutException → MODEL_TIMEOUT，retryable=true")
    void socketTimeoutIsClassifiedAsTimeout() {
        FailureClassifier.Failure f = FailureClassifier.classify(new SocketTimeoutException("read timed out"));
        assertThat(f.code()).isEqualTo(ErrorCode.MODEL_TIMEOUT);
        assertThat(f.retryable()).isTrue();
    }
}
