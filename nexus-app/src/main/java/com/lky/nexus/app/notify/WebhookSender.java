package com.lky.nexus.app.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.nexus.app.infra.net.OutboundUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 投递：POST 一段 JSON，可选 HMAC-SHA256 签名。
 *
 * <p>几个刻意的取舍：
 * <ul>
 *   <li>请求体只放 {@code event / title / body / at} 四个字段，不放文档全文、文件路径、token ——
 *       投递目标是用户自填地址，多传一个字段就是把用户数据多送出去一份；</li>
 *   <li>签名头是 {@code X-Nexus-Signature: sha256=<hex>}，对方用同一个 secret 对<b>原始字节</b>
 *       重算即可校验；不签名时该头不出现（而不是发一个空值）；</li>
 *   <li>非 2xx、超时、连接失败都抛出，由调用方写进 {@code last_error}；
 *       <b>不重试</b> —— 同一事件重复投递会把用户的群机器人刷屏，重试交给用户手动点「测试」。</li>
 * </ul>
 */
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final String USER_AGENT = "Prisdvl-Nexus/1.0";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean allowPrivateEndpoints;
    private final Duration timeout;

    public WebhookSender(ObjectMapper objectMapper, boolean allowPrivateEndpoints, Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(),
                objectMapper, allowPrivateEndpoints, timeout);
    }

    /** 测试注入自定义客户端（指向本地 MockWebServer）。 */
    WebhookSender(HttpClient httpClient,
                  ObjectMapper objectMapper,
                  boolean allowPrivateEndpoints,
                  Duration timeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.allowPrivateEndpoints = allowPrivateEndpoints;
        this.timeout = timeout;
    }

    /**
     * 投递一条通知。
     *
     * @param target 用户填的回调地址
     * @param secret 已解密的签名密钥；null / 空串表示不签名
     * @throws IllegalStateException 投递失败（消息可直接展示给用户）
     */
    public void send(String target, String secret, NotifyEvent event, String title, String body) {
        // 投递前再校验一次地址：保存时校验过，但 DNS 会变，而这里才是真正出网的地方
        URI uri = OutboundUrlGuard.requireHttpTarget(target, "Webhook 地址", allowPrivateEndpoints);

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(payloadOf(event, title, body));
        } catch (IOException ex) {
            throw new IllegalStateException("通知内容序列化失败", ex);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("X-Nexus-Event", event.code())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (secret != null && !secret.isBlank()) {
            builder.header("X-Nexus-Signature", SIGNATURE_PREFIX + hmacSha256Hex(secret, payload));
        }

        HttpResponse<Void> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Webhook 投递失败 event={} host={}", event.code(), uri.getHost());
            throw new IllegalStateException("Webhook 请求失败：" + ex.getClass().getSimpleName());
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.warn("Webhook 返回非 2xx event={} status={}", event.code(), status);
            throw new IllegalStateException("Webhook 返回 HTTP " + status);
        }
        log.info("Webhook 投递成功 event={} host={} status={}", event.code(), uri.getHost(), status);
    }

    /** 保存通道时要用同一套内网规则，所以把开关暴露出来，避免两处配置各写一份。 */
    public boolean allowsPrivateEndpoints() {
        return allowPrivateEndpoints;
    }

    private static Map<String, Object> payloadOf(NotifyEvent event, String title, String body) {        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event.code());
        payload.put("title", title);
        payload.put("body", body);
        payload.put("at", java.time.OffsetDateTime.now().toString());
        return payload;
    }

    static String hmacSha256Hex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (java.security.GeneralSecurityException ex) {
            // 算法/密钥在本机不成立属于部署错误，不是用户输入问题
            throw new IllegalStateException("无法计算 Webhook 签名", ex);
        }
    }
}
