package com.lky.nexus.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingByokPolicy;
import com.lky.nexus.common.exception.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CustomEmbeddingProbe} 的单测：用 JDK 自带的 HttpServer 当桩，不起 Spring、不连外网。
 *
 * <p>覆盖三件事：①真的按 OpenAI 协议问一次并读出 {@code data[0].embedding} 的长度；
 * ②端点返回非 2xx / 结构不对时给可读的 1001；③SSRF 闸门默认拒绝内网地址，
 * 且**在发请求之前**就拒绝（桩服务一次都没有被访问）。
 */
class CustomEmbeddingProbeTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    @DisplayName("探针把 data[0].embedding 的长度如实报出来（1024 与 768 都照实说）")
    void reportsActualDimensions() {
        stub("/v1/embeddings", 200, embeddingJson(1024));
        assertThat(probe(true).probe(baseUrl, "sk-user", "text-embedding-v4")).isEqualTo(1024);
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-user");
        assertThat(lastBody.get()).contains("\"model\":\"text-embedding-v4\"");

        stub("/v1/embeddings", 200, embeddingJson(768));
        assertThat(probe(true).probe(baseUrl, "sk-user", "bge-m3")).isEqualTo(768);
    }

    @Test
    @DisplayName("Base URL 已带 /embeddings 时不重复拼接")
    void doesNotDoubleAppendEndpoint() {
        stub("/v1/embeddings", 200, embeddingJson(1024));
        assertThat(probe(true).probe(baseUrl + "/embeddings", "sk-user", "m")).isEqualTo(1024);
    }

    @Test
    @DisplayName("端点返回 401 / 非 JSON / 缺 data[0].embedding：都是 1001，且消息能指导用户改哪一项")
    void rejectsBadResponses() {
        stub("/v1/embeddings", 401, "{\"error\":{\"message\":\"invalid api key\"}}");
        assertThatThrownBy(() -> probe(true).probe(baseUrl, "sk-bad", "m"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HTTP 401");

        stub("/v1/embeddings", 200, "not-json");
        assertThatThrownBy(() -> probe(true).probe(baseUrl, "sk-user", "m"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是合法 JSON");

        stub("/v1/embeddings", 200, "{\"data\":[]}");
        assertThatThrownBy(() -> probe(true).probe(baseUrl, "sk-user", "m"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("data[0].embedding");
    }

    @Test
    @DisplayName("SSRF 闸门：默认拒绝环回/内网地址，且请求根本不会发出去")
    void blocksPrivateHosts() {
        stub("/v1/embeddings", 200, embeddingJson(1024));
        assertThatThrownBy(() -> probe(false).probe(baseUrl, "sk-user", "m"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内网或本机");
        assertThat(lastBody.get()).as("被拦下的地址不应产生任何请求").isNull();

        assertThatThrownBy(() -> probe(true).probe("ftp://example.com/v1", "sk", "m"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("http / https");
    }

    private CustomEmbeddingProbe probe(boolean allowPrivate) {
        AiModelProperties properties = new AiModelProperties();
        properties.getByok().getEmbedding().setAllowPrivateEndpoints(allowPrivate);
        return new CustomEmbeddingProbe(new EmbeddingByokPolicy(properties));
    }

    /** 注册一个固定响应；多次调用后注册的路径会覆盖前一次（同一个 context）。 */
    private void stub(String path, int status, String body) {
        try { server.removeContext(path); } catch (IllegalArgumentException ignored) { /* 首次注册 */ }
        server.createContext(path, exchange -> respond(exchange, status, body));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String embeddingJson(int dimensions) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append("0.01");
        }
        return "{\"data\":[{\"embedding\":[" + values + "],\"index\":0}],\"model\":\"stub\"}";
    }
}
