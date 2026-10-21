package com.lky.nexus.app.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Webhook 投递的线级行为：发什么、签什么、失败怎么表现。
 *
 * <p>用 JDK 自带 {@link HttpServer} 起桩，不引第三方 mock 库（与 {@code ResendEmailSenderTest} 一致）。
 */
@DisplayName("WebhookSender")
class WebhookSenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();

    private record Captured(String body, String signature, String event) {
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            captured.add(new Captured(new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("X-Nexus-Signature"),
                    exchange.getRequestHeaders().getFirst("X-Nexus-Event")));
            exchange.sendResponseHeaders(status, -1);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[0]);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private WebhookSender sender() {
        return new WebhookSender(MAPPER, true, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("投递：JSON 只有 event/title/body/at，且按约定签名")
    void sendsSignedMinimalPayload() throws Exception {
        String target = start(200);

        sender().send(target, "s3cret", NotifyEvent.IMPORT_DONE, "导入完成：笔记", "新增 3 / 失败 0。");

        assertThat(captured).hasSize(1);
        Captured got = captured.get(0);
        JsonNode json = MAPPER.readTree(got.body());
        assertThat(json.get("event").asText()).isEqualTo("import.done");
        assertThat(json.get("title").asText()).isEqualTo("导入完成：笔记");
        assertThat(json.get("body").asText()).isEqualTo("新增 3 / 失败 0。");
        assertThat(json.has("at")).isTrue();
        // 除这四个字段外不许多传：投递目标是用户自填地址，多一个字段就是多送一份数据
        assertThat(json.size()).isEqualTo(4);
        assertThat(got.event()).isEqualTo("import.done");
        assertThat(got.signature()).startsWith("sha256=");

        String expected = "sha256=" + WebhookSender.hmacSha256Hex("s3cret", got.body().getBytes(StandardCharsets.UTF_8));
        assertThat(got.signature()).isEqualTo(expected);
    }

    @Test
    @DisplayName("没有密钥就不发签名头（而不是发一个空值）")
    void omitsSignatureHeaderWithoutSecret() throws Exception {
        String target = start(200);

        sender().send(target, null, NotifyEvent.IMPORT_FAILED, "t", "b");

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).signature()).isNull();
    }

    @Test
    @DisplayName("非 2xx 视为失败，异常文案可直接给用户")
    void non2xxFails() throws Exception {
        String target = start(500);

        assertThatThrownBy(() -> sender().send(target, null, NotifyEvent.IMPORT_DONE, "t", "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("允许内网时 127.0.0.1 可用；不允许时保存/投递都被拦")
    void privateHostGatedByFlag() throws Exception {
        String target = start(200);
        WebhookSender strict = new WebhookSender(MAPPER, false, Duration.ofSeconds(2));

        assertThatThrownBy(() -> strict.send(target, null, NotifyEvent.IMPORT_DONE, "t", "b"))
                .hasMessageContaining("内网");
        assertThat(captured).isEmpty();
    }
}
