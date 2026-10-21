package com.lky.nexus.app.auth.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resend HTTP API 发信：{@code POST /emails}，Bearer API Key。
 *
 * <p>不引入 Resend SDK；使用 JDK {@link HttpClient}。失败一律抛业务异常，绝不伪装成功。
 * 日志只记收件人与用途，不记验证码、不记 API Key、不记请求/响应原文。
 *
 * <p>本类不做自动重试：外部 API 失败时结果可能不确定，重试由用户再次点击发送
 * （{@link EmailCodeService} 会在失败时删码并释放冷却）。
 */
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private static final String DEFAULT_BASE_URL = "https://api.resend.com";
    private static final String SEND_PATH = "/emails";
    /** Resend 要求带 User-Agent，缺省会 403；固定产品标识，不含密钥。 */
    private static final String USER_AGENT = "Prisdvl-Nexus/1.0";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String from;
    private final URI sendUri;
    private final Duration requestTimeout;

    public ResendEmailSender(NexusEmailProperties.Resend resend) {
        this(createHttpClient(resend), new ObjectMapper(), requireApiKey(resend), requireFrom(resend),
                resolveBaseUrl(resend), resolveReadTimeout(resend));
    }

    /** 测试注入自定义客户端与 baseUrl（指向 Mock HTTP）。 */
    ResendEmailSender(HttpClient httpClient,
                      ObjectMapper objectMapper,
                      String apiKey,
                      String from,
                      String baseUrl,
                      Duration requestTimeout) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "nexus.email.sender=resend 时必须配置 nexus.email.resend.api-key（环境变量 RESEND_API_KEY）");
        }
        if (!StringUtils.hasText(from)) {
            throw new IllegalStateException(
                    "nexus.email.sender=resend 时必须配置 nexus.email.resend.from");
        }
        this.httpClient = httpClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.apiKey = apiKey.trim();
        this.from = from.trim();
        this.sendUri = URI.create(trimTrailingSlash(baseUrl) + SEND_PATH);
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(15);
    }

    @Override
    public void sendCode(String email, String code, EmailPurpose purpose, Duration expire) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(buildPayload(email, code, purpose, expire));
        } catch (IOException ex) {
            // 序列化失败不含密钥；不把 cause 细节回给客户端
            log.warn("验证码邮件请求体构造失败 purpose={}", purpose.keyToken());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(sendUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                log.info("验证码邮件已提交 Resend to={} purpose={}", email, purpose.keyToken());
                return;
            }
            // 不记录响应体（可能含供应商细节）；不记录 Authorization
            log.warn("验证码邮件发送失败 to={} purpose={} httpStatus={}",
                    email, purpose.keyToken(), status);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试");
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            log.warn("验证码邮件发送失败（网络） to={} purpose={}", email, purpose.keyToken());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("验证码邮件发送被中断 to={} purpose={}", email, purpose.keyToken());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试", ex);
        }
    }

    @Override
    public void sendNotice(String email, String title, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", List.of(email));
        payload.put("subject", title);
        payload.put("text", body);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(payload);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "通知邮件发送失败", ex);
        }
        HttpRequest request = HttpRequest.newBuilder(sendUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("通知邮件发送失败 to={} httpStatus={}", email, response.statusCode());
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "通知邮件发送失败");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "通知邮件发送失败", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "通知邮件发送被中断", ex);
        }
    }

    private Map<String, Object> buildPayload(String email, String code, EmailPurpose purpose, Duration expire) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", List.of(email));
        payload.put("subject", subjectOf(purpose));
        payload.put("html", htmlBodyOf(code, purpose, expire));
        payload.put("text", textBodyOf(code, purpose, expire));
        return payload;
    }

    static String subjectOf(EmailPurpose purpose) {
        return switch (purpose) {
            case VERIFY_EMAIL -> "Prisdvl-Nexus 邮箱验证码";
            case REGISTER -> "Prisdvl-Nexus 注册验证码";
            case RESET_PASSWORD -> "Prisdvl-Nexus 密码重置验证码";
        };
    }

    static String textBodyOf(String code, EmailPurpose purpose, Duration expire) {
        String action = actionOf(purpose);
        long minutes = Math.max(1, expire.toMinutes());
        return "您正在进行" + action + "操作。\n\n"
                + "验证码：" + code + "\n\n"
                + "有效期 " + minutes + " 分钟，只能使用一次。\n"
                + "请勿向他人透露验证码。\n"
                + "如非本人操作，请忽略本邮件。";
    }

    static String htmlBodyOf(String code, EmailPurpose purpose, Duration expire) {
        String action = actionOf(purpose);
        long minutes = Math.max(1, expire.toMinutes());
        // 验证码为数字，无需 HTML 转义；不嵌入其它用户信息
        return "<p>您正在进行" + action + "操作。</p>"
                + "<p>验证码：<strong>" + code + "</strong></p>"
                + "<p>有效期 " + minutes + " 分钟，只能使用一次。</p>"
                + "<p>请勿向他人透露验证码。</p>"
                + "<p>如非本人操作，请忽略本邮件。</p>";
    }

    private static String actionOf(EmailPurpose purpose) {
        return switch (purpose) {
            case VERIFY_EMAIL -> "验证当前绑定邮箱";
            case REGISTER -> "完成注册";
            case RESET_PASSWORD -> "重置密码";
        };
    }

    private static HttpClient createHttpClient(NexusEmailProperties.Resend resend) {
        Duration connect = resend.getConnectTimeout() != null
                ? resend.getConnectTimeout() : Duration.ofSeconds(5);
        return HttpClient.newBuilder()
                .connectTimeout(connect)
                .build();
    }

    private static String requireApiKey(NexusEmailProperties.Resend resend) {
        if (resend == null || !StringUtils.hasText(resend.getApiKey())) {
            throw new IllegalStateException(
                    "nexus.email.sender=resend 时必须配置 nexus.email.resend.api-key（环境变量 RESEND_API_KEY）");
        }
        return resend.getApiKey().trim();
    }

    private static String requireFrom(NexusEmailProperties.Resend resend) {
        if (resend == null || !StringUtils.hasText(resend.getFrom())) {
            throw new IllegalStateException(
                    "nexus.email.sender=resend 时必须配置 nexus.email.resend.from");
        }
        return resend.getFrom().trim();
    }

    private static String resolveBaseUrl(NexusEmailProperties.Resend resend) {
        if (resend != null && StringUtils.hasText(resend.getBaseUrl())) {
            return resend.getBaseUrl().trim();
        }
        return DEFAULT_BASE_URL;
    }

    private static Duration resolveReadTimeout(NexusEmailProperties.Resend resend) {
        if (resend != null && resend.getReadTimeout() != null) {
            return resend.getReadTimeout();
        }
        return Duration.ofSeconds(15);
    }

    private static String trimTrailingSlash(String baseUrl) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
