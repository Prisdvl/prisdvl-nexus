package com.lky.nexus.app.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.nexus.ai.model.EmbeddingByokPolicy;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「自定义嵌入端点」的保存期探针：真发一次 {@code POST {baseUrl}/embeddings}，
 * 拿回向量长度，用来决定这次保存能不能落库。
 *
 * <p>为什么必须真打一次，而不是让用户自己声明维度：
 * 向量列写死 {@code vector(1024)}，用户填错维度**不会**报错——只会表现为
 * 「检索结果莫名其妙」（{@code docs/decisions.md} 的 ADR-008）。
 * 白名单里的模型是平台验证过的，所以走白名单不需要探针；自定义端点没人能保证，
 * 就只能当场证明：探针拿到的维度不等于平台允许维度，直接 400。
 *
 * <p><b>SSRF</b>：Base URL 是用户输入，直接拿服务器去请求它等于给了一个内网探测接口。
 * 默认拒绝环回 / 私网 / 链路本地地址（{@code ai.byok.embedding.allow-private-endpoints}
 * 只在本机开发与测试 profile 打开——那时端点是 localhost 上的桩服务）。
 */
@Component
public class CustomEmbeddingProbe {

    private static final Logger log = LoggerFactory.getLogger(CustomEmbeddingProbe.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** 探针正文固定用这个短句：几乎所有 OpenAI 兼容实现都能接受单条短输入。 */
    private static final List<String> PROBE_INPUT = List.of("dimension-probe");

    /** 自建 mapper：这里只读写两三个字段，不值得依赖容器里的 ObjectMapper bean。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EmbeddingByokPolicy byokPolicy;
    private final HttpClient httpClient;

    public CustomEmbeddingProbe(EmbeddingByokPolicy byokPolicy) {
        this.byokPolicy = byokPolicy;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 探针并返回端点实际返回的向量维度。
     *
     * @param baseUrl   OpenAI 兼容 Base URL（如 {@code https://host/v1}）
     * @param apiKey    调用方自己的 Key（明文，仅用于这一次探针，不落任何日志）
     * @param modelName 模型名（如 {@code text-embedding-v4}）
     * @return 端点返回的向量长度
     * @throws BusinessException 400/1001，消息可直接展示给用户
     */
    public int probe(String baseUrl, String apiKey, String modelName) {
        URI endpoint = resolveEndpoint(baseUrl);
        String body = toJson(Map.of("model", modelName, "input", PROBE_INPUT));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 不透出底层异常文本：里面有内网地址，也可能带请求细节
            log.info("自定义嵌入端点探针请求失败：{}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "连不上自定义嵌入端点，请确认 Base URL 可访问（当前填的是 " + endpoint.getHost() + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "校验自定义嵌入端点时被中断，请重试");
        }

        if (response.statusCode() / 100 != 2) {
            log.info("自定义嵌入端点探针返回 HTTP {}", response.statusCode());
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "自定义端点返回 HTTP %d，请确认它是 OpenAI 兼容的 /embeddings 端点、Key 与模型名正确"
                            .formatted(response.statusCode()));
        }
        return readDimensions(response.body());
    }

    /** 解析 {@code data[0].embedding} 的长度；结构不对就当作"端点不兼容"。 */
    private int readDimensions(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode embedding = root.path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "自定义端点返回的不是 OpenAI 兼容的嵌入响应（缺 data[0].embedding）");
            }
            return embedding.size();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "自定义端点的响应不是合法 JSON");
        }
    }

    /** 拼出 {@code .../embeddings}，同时做 SSRF 与协议校验。 */
    private URI resolveEndpoint(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "自定义端点必须填写 Base URL");
        }
        URI base;
        try {
            base = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Base URL 不是合法的 URL");
        }
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Base URL 只支持 http / https");
        }
        if (base.getHost() == null || base.getHost().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Base URL 缺少主机名");
        }
        if (!byokPolicy.allowsPrivateEndpoints()) {
            requirePublicHost(base.getHost());
        }
        String path = base.getPath() == null ? "" : base.getPath();
        String normalized = path.endsWith("/embeddings")
                ? trimmed
                : trimmed.replaceAll("/+$", "") + "/embeddings";
        try {
            return new URI(normalized);
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Base URL 拼出的 /embeddings 地址不合法");
        }
    }

    /**
     * 拒绝指向内网的主机（含 localhost 与私有/链路本地 IP）。
     *
     * <p>这里用解析后的地址判断而不是字符串匹配：{@code http://127.0.0.1}、
     * {@code http://[::1]}、{@code http://internal-host} 都得拦住。
     */
    private void requirePublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "无法解析 Base URL 的主机名：" + host);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "Base URL 不能指向内网或本机地址（" + host + "）");
            }
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "构造探针请求失败");
        }
    }
}
